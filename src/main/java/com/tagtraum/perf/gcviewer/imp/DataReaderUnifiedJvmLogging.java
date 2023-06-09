package com.tagtraum.perf.gcviewer.imp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.tagtraum.perf.gcviewer.model.AbstractGCEvent;
import com.tagtraum.perf.gcviewer.model.AbstractGCEvent.Concurrency;
import com.tagtraum.perf.gcviewer.model.AbstractGCEvent.ExtendedType;
import com.tagtraum.perf.gcviewer.model.AbstractGCEvent.GcPattern;
import com.tagtraum.perf.gcviewer.model.AbstractGCEvent.Type;
import com.tagtraum.perf.gcviewer.model.ConcurrentGCEvent;
import com.tagtraum.perf.gcviewer.model.GCEvent;
import com.tagtraum.perf.gcviewer.model.GCEventUJL;
import com.tagtraum.perf.gcviewer.model.GCModel;
import com.tagtraum.perf.gcviewer.model.GCResource;
import com.tagtraum.perf.gcviewer.model.VmOperationEvent;
import com.tagtraum.perf.gcviewer.util.DateHelper;
import com.tagtraum.perf.gcviewer.util.NumberParser;

/**
 * DataReaderUnifiedJvmLogging can parse all gc events of unified jvm logs with default decorations.
 * <p>
 * Currently needs the "gc" selector with "info" level and "uptime,level,tags" (or "time,level,tags") decorators (Java 9.0.1).
 * Also supports "gc*" selector with "trace" level and "time,uptime,level,tags" decorators, but will ignore some of
 * the debug and all trace level info (evaluates the following tags: "gc", "gc,start", "gc,heap", "gc,metaspace".
 * <ul>
 * <li>minimum configuration with defaults supported: <code>-Xlog:gc:file="path-to-file"</code></li>
 * <li>explicit minimum configuration needed: <code>-Xlog:gc=info:file="path-to-file":uptime,level,tags</code> or <code>-Xlog:gc=info:file="path-to-file":time,level,tags</code></li>
 * <li>maximum detail configuration this parser is able to work with: <code>-Xlog:gc*=trace:file="path-to-file":time,uptime,timemillis,uptimemillis,timenanos,uptimenanos,pid,tid,level,tags</code></li>
 * </ul>
 * Only processes the following information format for Serial, Parallel, CMS, G1 and Shenandoah algorithms, everything else is ignored:
 * <pre>
 * [0.731s][info][gc           ] GC(0) Pause Init Mark 1.021ms
 * [0.735s][info][gc           ] GC(0) Concurrent marking 74M-&gt;74M(128M) 3.688ms
 * [43.948s][info][gc             ] GC(831) Pause Full (Allocation Failure) 7943M-&gt;6013M(8192M) 14289.335ms
 * </pre>
 *
 * <p>
 * For more information about Shenandoah see: <a href="https://wiki.openjdk.java.net/display/shenandoah/Main">Shenandoah Wiki at OpenJDK</a>
 */
public class DataReaderUnifiedJvmLogging extends AbstractDataReader {
    // TODO also parse "Allocation Stall (main)" events

    // matches the whole line and extracts decorators from it (decorators always appear between [] and are independent of the gc algorithm being logged)
    // Input: [2023-01-01T00:00:14.206+0000][14.206s][1672531214206ms][14205ms][1000014205707082ns][14205707082ns][6000][6008][info ][gc                      ] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 4115M->103M(8192M) 28.115ms
    // reference: https://openjdk.org/jeps/158
    // Group  1 / time: 2023-01-01T00:00:14.206+0800 (Current time and date in ISO-8601 format)
    // Group  2 / uptime: 14.206s (Time since the start of the JVM in seconds and milliseconds)
    // Group  3 / timemillis: 1672531214206ms (The same value as generated by System.currentTimeMillis())
    // Group  4 / uptimemillis: 14205ms (Milliseconds since the JVM started)
    // Group  5 / timenanos: 1000014205707082ns (The same value as generated by System.nanoTime())
    // Group  6 / uptimenanos: 14205707082ns (Nanoseconds since the JVM started)
    // Group  7 / pid: 6000 (The process identifier)
    // Group  8 / tid: 6008 (The thread identifier)
    // Group  9 / level: info (The level associated with the log message)
    // Group 10 / tags: gc (The tag-set associated with the log message)
    // Group 11 / gcnumber: 0
    // Group 12 / tail: Pause Init Mark 1.070ms
    // Regex: (see the below)
    //   note for the <type> part: easiest would have been to use [^0-9]+, but the G1 events don't fit there, because of the number in their name
    //   add sub regex "[a-zA-Z ]+\\(.+\\)" for Allocation Stall and Relocation Stall of ZGC
    private static final Pattern PATTERN_DECORATORS = Pattern.compile(
            "^" +
                    "(?:\\[(?<time>[0-9-T:.+]*)])?" +
                    "(?:\\[(?<uptime>[0-9.,]+)s[ ]*])?" +
                    "(?:\\[(?<timemillis>[0-9]+)ms[ ]*])?" +
                    "(?:\\[(?<uptimemillis>[0-9]+)ms[ ]*])?" +
                    "(?:\\[(?<timenanos>[0-9]+)ns[ ]*])?" +
                    "(?:\\[(?<uptimenanos>[0-9]+)ns[ ]*])?" +
                    "(?:\\[(?<pid>[0-9]+)[ ]*])?" +
                    "(?:\\[(?<tid>[0-9]+)[ ]*])?" +
                    "\\[(?<level>[^]]+)]" +
                    "\\[(?:(?<tags>[^] ]+)[ ]*)]" +
                    "[ ]" +
                    "(GC\\((?<gcnumber>[0-9]+)\\)[ ])?" +
                    "(?<type>(?:Phase [0-9]{1}: [a-zA-Z ]+)|[-.a-zA-Z: ()]+|[a-zA-Z1 ()]+|[a-zA-Z ]+\\(.+\\))" +
                    "(?:(?:[ ](?<tail>[0-9]{1}.*))|$)"
    );
    private static final String GROUP_DECORATORS_TIME = "time";
    private static final String GROUP_DECORATORS_UPTIME = "uptime";
    private static final String GROUP_DECORATORS_TIME_MILLIS = "timemillis";
    private static final String GROUP_DECORATORS_UPTIME_MILLIS = "uptimemillis";
    private static final String GROUP_DECORATORS_TIME_NANOS = "timenanos";
    private static final String GROUP_DECORATORS_UPTIME_NANOS = "uptimenanos";
    private static final String GROUP_DECORATORS_PID = "pid";
    private static final String GROUP_DECORATORS_TID = "tid";
    private static final String GROUP_DECORATORS_LEVEL = "level";
    private static final String GROUP_DECORATORS_TAGS = "tags";
    private static final String GROUP_DECORATORS_GC_NUMBER = "gcnumber";
    private static final String GROUP_DECORATORS_GC_TYPE = "type";
    private static final String GROUP_DECORATORS_TAIL = "tail";

    private static final long MIN_VALID_UNIX_TIME_MILLIS = 1000000000000L; // 2001-09-09 09:46:40

    private static final Pattern PATTERN_HEAP_REGION_SIZE = Pattern.compile("^Heap [Rr]egion [Ss]ize: ([0-9]+)M$");
    private static final int GROUP_HEAP_REGION_SIZE = 1;

    private static final String PATTERN_PAUSE_STRING = "([0-9]+[.,][0-9]+)ms";
    /** 257K(448K)->257K(448K) - first "(448K)" is optional */
    private static final String PATTERN_MEMORY_STRING = "(([0-9]+)([BKMG])(?:\\([0-9]+[BKMG]\\))?->([0-9]+)([BKMG])\\(([0-9]+)([BKMG])\\))";

    private static final String PATTERN_HEAP_MEMORY_PERCENTAGE_STRING = "(([0-9]+)([BKMG])[ ](\\([0-9]+%\\)))";
    private static final String PATTERN_MEMORY_PERCENTAGE_STRING = "(([0-9]+)([BKMG])\\(([0-9]+)%\\)->([0-9]+)([BKMG])\\(([0-9]+)%\\))";

    // Input: 1.070ms
    // Group 1: 1.070
    private static final Pattern PATTERN_PAUSE = Pattern.compile("^" + PATTERN_PAUSE_STRING);

    private static final int GROUP_PAUSE = 1;

    // Input: 4848M->4855M(4998M)
    // Group 1: 4848M->4855M(4998M)
    // Group 2: 4848
    // Group 3: M
    // Group 4: 4855
    // Group 5: M
    // Group 6: 4998
    // Group 7: M
    private static final Pattern PATTERN_MEMORY = Pattern.compile("^" + PATTERN_MEMORY_STRING);

    // Input: 4848M->4855M(4998M) 2.872ms
    // Group 1: 4848M->4855M(4998M)
    // Group 2: 4848
    // Group 3: M
    // Group 4: 4855
    // Group 5: M
    // Group 6: 4998
    // Group 7: M
    // Group 8: 2.872 (optional group)
    private static final Pattern PATTERN_MEMORY_PAUSE = Pattern.compile("^" + PATTERN_MEMORY_STRING + "(?:(?:[ ]" + PATTERN_PAUSE_STRING + ")|$)?");

    private static final int GROUP_MEMORY = 1;
    private static final int GROUP_MEMORY_BEFORE = 2;
    private static final int GROUP_MEMORY_BEFORE_UNIT = 3;
    private static final int GROUP_MEMORY_AFTER = 4;
    private static final int GROUP_MEMORY_AFTER_UNIT = 5;
    private static final int GROUP_MEMORY_CURRENT_TOTAL = 6;
    private static final int GROUP_MEMORY_CURRENT_TOTAL_UNIT = 7;
    private static final int GROUP_MEMORY_PAUSE = 8;

    // Input: 7->3(2)
    // Group 1: 7
    // Group 2: 3
    // Group 3: 2 (optional group)
    private static final Pattern PATTERN_REGION = Pattern.compile("^([0-9]+)->([0-9]+)(?:\\(([0-9]+)\\))?");

    private static final int GROUP_REGION_BEFORE = 1;
    private static final int GROUP_REGION_AFTER = 2;
    private static final int GROUP_REGION_TOTAL = 3;

    // Input: 106M(0%)->88M(0%)
    // Group 1: 106M(0%)->88M(0%)
    // Group 2: 106
    // Group 3: M
    // Group 4: 0%
    // Group 5: 88
    // Group 6: M
    // Group 7: 0%
    private static final Pattern PATTERN_MEMORY_PERCENTAGE = Pattern.compile("^" + PATTERN_MEMORY_PERCENTAGE_STRING);
    
    private static final int GROUP_MEMORY_PERCENTAGE = 1;
    private static final int GROUP_MEMORY_PERCENTAGE_BEFORE = 2;
    private static final int GROUP_MEMORY_PERCENTAGE_BEFORE_UNIT = 3;
    private static final int GROUP_MEMORY_PERCENTAGE_BEFORE_PERCENTAGE = 4;
    private static final int GROUP_MEMORY_PERCENTAGE_AFTER = 5;
    private static final int GROUP_MEMORY_PERCENTAGE_AFTER_UNIT = 6;
    private static final int GROUP_MEMORY_PERCENTAGE_AFTER_PERCENTAGE = 7;

    // Input: 300M (1%)
    // Group 1: 300M (1%)
    // Group 2: 300
    // Group 3: M
    // Group 4: (1%)
    private static final Pattern PATTERN_HEAP_MEMORY_PERCENTAGE = Pattern.compile("^" + PATTERN_HEAP_MEMORY_PERCENTAGE_STRING);

    private static final int GROUP_HEAP_MEMORY_PERCENTAGE = 1;
    private static final int GROUP_HEAP_MEMORY_PERCENTAGE_VALUE = 2;
    private static final int GROUP_HEAP_MEMORY_PERCENTAGE_UNIT = 3;

    private static final String TAG_GC = "gc";
    private static final String TAG_GC_START = "gc,start";
    private static final String TAG_GC_HEAP = "gc,heap";
    private static final String TAG_GC_METASPACE = "gc,metaspace";
    private static final String TAG_GC_PHASES = "gc,phases";
    private static final String TAG_GC_INIT = "gc,init";
    private static final String TAG_SAFEPOINT = "safepoint";
    
    /** list of strings, that must be part of the gc log line to be considered for parsing */
    private static final List<String> INCLUDE_STRINGS = Arrays.asList("[gc ", "[gc]", "[" + TAG_GC_START, "[" + TAG_GC_HEAP, "[" + TAG_GC_METASPACE, "[" + TAG_GC_PHASES, "[" + TAG_GC_INIT, Type.APPLICATION_STOPPED_TIME.getName());
    /** list of strings, that target gc log lines, that - although part of INCLUDE_STRINGS - are not considered a gc event */
    private static final List<String> EXCLUDE_STRINGS = Arrays.asList("Cancelling concurrent GC",
            "[debug",
            "[trace",
            "gc,heap,coops",
            "gc,heap,exit",
            "gc,metaspace,freelist,oom",
            "[gc,phases,start",
            "Trigger: ",
            "Failed to allocate",
            "Cancelling GC",
            "CDS archive(s) mapped at", // metaspace preamble since JDK 17
            "Compressed class space mapped at", // metaspace preamble since JDK 17
            "Narrow klass base", // metaspace preamble since JDK 17
            "  Mark Start  ", // heap preamble for ZGC since JDK 11
            "Reserve:", // heap preamble for ZGC since JDK 11
            "Free:", // heap preamble for ZGC since JDK 11
            "Used:", // heap preamble for ZGC since JDK 11
            "Live:", // heap preamble for ZGC since JDK 11
            "Allocated:", // heap preamble for ZGC since JDK 11
            "Garbage:", // heap preamble for ZGC since JDK 11
            "Reclaimed:", // heap preamble for ZGC since JDK 11
            "Page Cache Flushed:", // heap preamble for ZGC since JDK 11
            "Min Capacity:", // heap preamble for ZGC since JDK 11
            "Max Capacity:", // heap preamble for ZGC since JDK 11
            "Soft Max Capacity:", // heap preamble for ZGC since JDK 11
            "Uncommitted:" // heap preamble for ZGC since JDK 11
            );
    /** list of strings, that are gc log lines, but not a gc event -&gt; should be logged only */
    private static final List<String> LOG_ONLY_STRINGS = Arrays.asList("Using",
            "Heap region size", // jdk 11
            "Heap Region Size", // jdk 17
            "Consider",
            "Heuristics ergonomically sets",
            "Soft Max Heap Size", // ShenandoahGC
            "[gc,init"
            );

    protected DataReaderUnifiedJvmLogging(GCResource gcResource, InputStream in) throws UnsupportedEncodingException {
        super(gcResource, in);
    }

    @Override
    public GCModel read() throws IOException {
        getLogger().info("Reading Oracle / OpenJDK unified jvm logging format...");

        try {
            // some information shared across several lines of parsing...
            Map<String, AbstractGCEvent<?>> partialEventsMap = new HashMap<>();
            Map<String, Object> infoMap = new HashMap<>();

            GCModel model = new GCModel();
            model.setFormat(GCModel.Format.UNIFIED_JVM_LOGGING);

            Stream<String> lines = in.lines();
            lines.map(line -> new ParseContext(line, partialEventsMap, infoMap))
                    .filter(this::lineContainsParseableEvent)
                    .map(this::parseEvent)
                    .filter(context -> context.getCurrentEvent() != null)
                    .forEach(context -> model.add(context.getCurrentEvent()));

            return model;
        } finally {
            if (getLogger().isLoggable(Level.INFO)) getLogger().info("Reading done.");
        }
    }

    private ParseContext parseEvent(ParseContext context) {
        AbstractGCEvent<?> event = null;
        Matcher decoratorsMatcher = PATTERN_DECORATORS.matcher(context.getLine());
        try {
            event = createGcEventWithStandardDecorators(decoratorsMatcher, context.getLine());
            if (event != null) {
                String tags = decoratorsMatcher.group(GROUP_DECORATORS_TAGS);
                String tail = decoratorsMatcher.group(GROUP_DECORATORS_TAIL);
                event = handleTail(context, event, tags, tail);
            }
        } catch (UnknownGcTypeException | NumberFormatException e) {
            // prevent incomplete event from being added to the GCModel
            event = null;
            getLogger().warning(String.format("Failed to parse gc event (%s) on line number %d (line=\"%s\")", e.toString(), in.getLineNumber(), context.getLine()));
        }

        context.setCurrentEvent(event);
        return context;
    }

    private AbstractGCEvent<?> handleTail(ParseContext context, AbstractGCEvent<?> event, String tags, String tail) {
        AbstractGCEvent<?> returnEvent = event;
        switch (tags) {
            case TAG_SAFEPOINT:
                returnEvent = handleTagSafepoint(context, event, tail);
                break;
            case TAG_GC_START:
                returnEvent = handleTagGcStartTail(context, event);
                break;
            case TAG_GC_HEAP:
                returnEvent = handleTagGcHeapTail(context, event, tail);
                // ZGC heap capacity, break out and handle next event
                if (returnEvent == null) {
                break;
                }
                // fallthrough -> same handling as for METASPACE event
            case TAG_GC_METASPACE:
                returnEvent = handleTagGcMetaspaceTail(context, event, tail);
                break;
            case TAG_GC:
                returnEvent = handleTagGcTail(context, event, tail);
                break;
            case TAG_GC_PHASES:
                returnEvent = handleTagGcPhasesTail(context, event, tail);
                break;
            default:
                getLogger().warning(String.format("Unexpected tail present in the end of line number %d (tail=\"%s\"; line=\"%s\")", in.getLineNumber(), tail, context.getLine()));
        }

        return returnEvent;
    }

    private AbstractGCEvent<?> handleTagSafepoint(ParseContext context, AbstractGCEvent<?> event, String tail) {
        event.setPause(NumberParser.parseDouble(tail.split(" ")[0]));
        return event;
    }

    private AbstractGCEvent<?> handleTagGcStartTail(ParseContext context, AbstractGCEvent<?> event) {
        // here, the gc type is known, and the partial events will need to be added later
        context.getPartialEventsMap().put(event.getNumber() + "", event);
        return null;
    }

    private AbstractGCEvent<?> handleTagGcPhasesTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        AbstractGCEvent<?> returnEvent = event;

        AbstractGCEvent<?> parentEvent = context.getPartialEventsMap().get(event.getNumber() + "");
        if (parentEvent instanceof GCEventUJL) {
            returnEvent = parseTail(context, returnEvent, tail);
            // ZGC logs concurrent events as phases of one gc event -> don't add them to the phases list
            // to comply with the current GCViewer implementation, which expects concurrent events in a separate list.
            // If later insights suggest keeping the concurrent events as part of one gc event, GCModel#add and
            // maybe some renderers need to be adjusted
            if (returnEvent.isConcurrent()) {
                return returnEvent;
            } else {
                parentEvent.addPhase(returnEvent);
                return null;
            }
        }

        return null;
    }

    private AbstractGCEvent<?> handleTagGcMetaspaceTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        AbstractGCEvent<?> returnEvent = event;
        // the event "Metaspace" in gc tag "[gc,metaspace]" for ZGC don't match the "PATTERN_MEMORY" rules; ignore it
        // ZGC:
        //  [1.182s][info][gc,metaspace] GC(0) Metaspace: 19M used, 19M capacity, 19M committed, 20M reserved
        //  [1.182s][info][gc,metaspace] GC(0) Metaspace: 11M used, 12M committed, 1088M reserved
        // G1:
        //  [5.537s][info][gc,metaspace] GC(0) Metaspace: 118K(320K)->118K(320K) NonClass: 113K(192K)->113K(192K) Class: 4K(128K)->4K(128K)
        if (returnEvent.getExtendedType().getType().equals(Type.METASPACE) && tail != null) {
            if (tail.contains("used,") && tail.contains("committed,")) {
                return null;
            }
        }
        // the event "Metaspace" in gc tag "[gc,metaspace]" for Shenandoah don't have GC number; ignore it
        // [5.063s][info][gc,metaspace] Metaspace: 13104K(13376K)->13192K(13440K) NonClass: 11345K(11456K)->11431K(11520K) Class: 1758K(1920K)->1761K(1920K)
         if (returnEvent.getNumber() < 0) {
             return null;
         }

        returnEvent = parseTail(context, returnEvent, tail);
        // the UJL "Old" event occurs often after the next STW events have taken place; ignore it for now
        //   size after concurrent collection will be calculated by GCModel#add()
        if (!returnEvent.getExtendedType().getType().equals(Type.UJL_CMS_CONCURRENT_OLD)) {
            updateEventDetails(context, returnEvent);
        }
        return null;
    }

    private AbstractGCEvent<?> handleTagGcTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        AbstractGCEvent<?> returnEvent = event;
        AbstractGCEvent<?> parentEvent = context.getPartialEventsMap().get(event.getNumber() + "");
        if (parentEvent != null) {
            if (parentEvent.getExtendedType().equals(returnEvent.getExtendedType())) {
                // date- and timestamp are always end of event -> adjust the parent event
                parentEvent.setDateStamp(event.getDatestamp());
                parentEvent.setTimestamp(event.getTimestamp());
                returnEvent = parseTail(context, parentEvent, tail);
                context.partialEventsMap.remove(event.getNumber() + "");
            } else {
                // more detail information is provided for the parent event
                updateEventDetails(context, returnEvent);
                returnEvent = null;
            }
        } else {
            returnEvent = parseTail(context, event, tail);
        }

        return returnEvent;
    }

    private AbstractGCEvent<?> handleTagGcHeapTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        AbstractGCEvent<?> returnEvent = event;
        AbstractGCEvent<?> parentEvent = context.getPartialEventsMap().get(event.getNumber() + "");
        // if ZGC heap capacity, record total heap for this event, then pass it on to record pre and post used heap
        if (event.getExtendedType().getType().equals(Type.UJL_ZGC_HEAP_CAPACITY) && parentEvent != null) {
            // Parse with correct pattern and match the total memory
            returnEvent = parseTail(context, event, tail);
            parentEvent.setTotal(returnEvent.getTotal());
            context.partialEventsMap.put(event.getNumber() + "", parentEvent);
            returnEvent = null;
        }
        return returnEvent;
    }

    private void updateEventDetails(ParseContext context, AbstractGCEvent<?> event) {
        AbstractGCEvent<?> parentEvent = context.getPartialEventsMap().get(event.getNumber() + "");
        if (parentEvent == null) {
            getLogger().warning(String.format("Didn't find parent event for partial event %s (line number %d, line=\"%s\"", event.toString(), in.getLineNumber(), context.getLine()));
        } else {
            if (parentEvent instanceof GCEvent) {
                ((GCEvent)parentEvent).add((GCEvent)event);
            } else {
                getLogger().warning(String.format("Parent (%s) event for %s should be GCEvent (line number %d, line=\"%s\"", parentEvent.toString(), event.toString(), in.getLineNumber(), context.getLine()));
            }
        }
    }

    private AbstractGCEvent<?> parseTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        if (event.getExtendedType().getPattern().equals(GcPattern.GC_PAUSE)) {
            parseGcPauseTail(context, event, tail);
        } else if (event.getExtendedType().getPattern().equals(GcPattern.GC_MEMORY)) {
            parseGcMemoryTail(context, event, tail);
        } else if (event.getExtendedType().getPattern().equals(GcPattern.GC_REGION)) {
            parseGcRegionTail(context, event, tail);
        } else if (event.getExtendedType().getPattern().equals(GcPattern.GC_MEMORY_PAUSE)) {
            parseGcMemoryPauseTail(context, event, tail);
        } else if (event.getExtendedType().getPattern().equals(GcPattern.GC) || event.getExtendedType().getPattern().equals(GcPattern.GC_PAUSE_DURATION)) {
            parseGcTail(context, tail);
        } else if (event.getExtendedType().getPattern().equals(GcPattern.GC_MEMORY_PERCENTAGE)) {
            parseGcMemoryPercentageTail(context, event, tail);
        } else if (event.getExtendedType().getPattern().equals(GcPattern.GC_HEAP_MEMORY_PERCENTAGE)) {
            parseGcHeapMemoryPercentageTail(context, event, tail);
        }

        return event;
    }

    private void parseGcTail(ParseContext context, String tail) {
        if (tail != null) {
            getLogger().warning(String.format("Unexpected tail present in the end of line number %d (expected nothing to be present, tail=\"%s\"; line=\"%s\")", in.getLineNumber(), tail, context.getLine()));
        }
    }

    private void parseGcMemoryTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        Matcher memoryMatcher = tail != null ? PATTERN_MEMORY.matcher(tail) : null;
        if (memoryMatcher != null && memoryMatcher.find()) {
            setMemory(event, memoryMatcher);
        } else {
            getLogger().warning(String.format("Expected only memory in the end of line number %d (line=\"%s\")", in.getLineNumber(), context.getLine()));
        }
    }

    private void parseGcMemoryPauseTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        Matcher memoryPauseMatcher = tail != null ? PATTERN_MEMORY_PAUSE.matcher(tail) : null;
        if (memoryPauseMatcher != null && memoryPauseMatcher.find()) {
            setPause(event, memoryPauseMatcher.group(GROUP_MEMORY_PAUSE));
            if (!hasMemory(event)) {
                // if the event already has detail memory information, there is no need to add the high level one as well
                setMemory(event, memoryPauseMatcher);
            }
        } else {
            getLogger().warning(String.format("Expected memory and pause in the end of line number %d (line=\"%s\")", in.getLineNumber(), context.getLine()));
        }
    }

    private void parseGcPauseTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        // G1 and CMS algorithms have "gc" tagged concurrent events, that are actually the start of an event
        // (I'd expect them to be tagged "gc,start", as this seems to be the case for the Shenandoah algorithm)
        // G1: Concurrent Cycle (without pause -> start of event; with pause -> end of event)
        // CMS: all concurrent events

        // this is the reason, why a "null" tail is accepted here
        if (tail != null) {
            Matcher pauseMatcher = PATTERN_PAUSE.matcher(tail);
            if (pauseMatcher.find()) {
                setPause(event, pauseMatcher.group(GROUP_PAUSE));
            } else {
                getLogger().warning(String.format("Expected only pause in the end of line number %d  (line=\"%s\")", in.getLineNumber(), context.getLine()));
            }
        }
    }

    private void parseGcRegionTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        Matcher regionMatcher = tail != null ? PATTERN_REGION.matcher(tail) : null;
        if (regionMatcher != null && regionMatcher.find()) {
            int regionSize = context.getRegionSize();
            // if the event has regions, but the regionSize is unknown, at the moment, I don't know, how to calculate the size
            // -> store 0 for the size
            // this happens, whenever a G1 log file is parsed and the line
            // [0.018s][info][gc,heap] Heap region size: 1M
            // is missing (only part of log present)
            event.setPreUsed(Integer.parseInt(regionMatcher.group(GROUP_REGION_BEFORE)) * regionSize * 1024);
            event.setPostUsed(Integer.parseInt(regionMatcher.group(GROUP_REGION_AFTER)) * regionSize * 1024);
            if (regionMatcher.group(GROUP_REGION_TOTAL) != null) {
                event.setTotal(Integer.parseInt(regionMatcher.group(GROUP_REGION_TOTAL)) * regionSize * 1024);
            }
        } else {
            getLogger().warning(String.format("Expected region information in the end of line number %d (line=\"%s\")", in.getLineNumber(), context.getLine()));
        }
    }
    
    private void parseGcMemoryPercentageTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        Matcher memoryPercentageMatcher = tail != null ? PATTERN_MEMORY_PERCENTAGE.matcher(tail) : null;
        if (memoryPercentageMatcher != null && memoryPercentageMatcher.find()) {
            // the end Garbage Collection tags in ZGC contain details of memory cleaned up
            // and the percentage of memory used before and after clean. The details can be used to 
            // determine Allocation rate.
            setMemoryWithPercentage(event, memoryPercentageMatcher);
        } else {
            getLogger().warning(String.format("Expected memory percentage in the end of line number %d (line=\"%s\")", in.getLineNumber(), context.getLine()));
        }
    }

    private void parseGcHeapMemoryPercentageTail(ParseContext context, AbstractGCEvent<?> event, String tail) {
        Matcher memoryPercentageMatcher = tail != null ? PATTERN_HEAP_MEMORY_PERCENTAGE.matcher(tail) : null;
        if (memoryPercentageMatcher != null && memoryPercentageMatcher.find()) {
            // Heap section in ZGC logs provide heap stats during the GC cycle
            // Currently using to get total heap size, percentage for total heap is not useful
            setMemoryHeapWithPercentage(event, memoryPercentageMatcher);
        } else {
            getLogger().warning(String.format("Expected heap memory percentage in the end of line number %d (line=\"%s\")", in.getLineNumber(), context.getLine()));
        }
    }

    /**
     * Returns an instance of AbstractGcEvent (GCEvent or ConcurrentGcEvent) with all decorators present filled in
     * or <code>null</code> if the line could not be matched.
     * @param decoratorsMatcher matcher for decorators to be used for GcEvent creation
     * @param line current line to be parsed
     * @return Instance of <code>AbstractGcEvent</code> or <code>null</code> if the line could not be matched.
     */
    private AbstractGCEvent<?> createGcEventWithStandardDecorators(Matcher decoratorsMatcher, String line) throws UnknownGcTypeException {
        if (decoratorsMatcher.find()) {
            AbstractGCEvent.ExtendedType type = getDataReaderTools().parseType(decoratorsMatcher.group(GROUP_DECORATORS_GC_TYPE));

            AbstractGCEvent<?> event = createGcEvent(type);
            event.setExtendedType(type);
            if (decoratorsMatcher.group(GROUP_DECORATORS_GC_NUMBER) != null) {
                event.setNumber(Integer.parseInt(decoratorsMatcher.group(GROUP_DECORATORS_GC_NUMBER)));
            }

            boolean hasTime = setDateStampIfPresent(event, decoratorsMatcher.group(GROUP_DECORATORS_TIME));
            boolean hasUptime = setTimeStampIfPresent(event, decoratorsMatcher.group(GROUP_DECORATORS_UPTIME), "s");

            // The second time is the uptime for sure when the text contains two pairs of millisecond time
            if (decoratorsMatcher.group(GROUP_DECORATORS_TIME_MILLIS) != null && decoratorsMatcher.group(GROUP_DECORATORS_UPTIME_MILLIS) != null) {
                if (!hasTime) {
                    long timeInMillisecond = Long.parseLong(decoratorsMatcher.group(GROUP_DECORATORS_TIME_MILLIS));
                    hasTime = setDateStampIfPresent(event, DateHelper.formatDate(ZonedDateTime.ofInstant(Instant.ofEpochMilli(timeInMillisecond), ZoneId.systemDefault()))) || hasTime;
                }

                hasUptime = setTimeStampIfPresent(event, decoratorsMatcher.group(GROUP_DECORATORS_UPTIME_MILLIS), "ms") || hasUptime;
            } else if (decoratorsMatcher.group(GROUP_DECORATORS_TIME_MILLIS) != null) {
                // If the first millisecond time below the valid unix time, it may be uptime, otherwise it may be unix time
                long millisecond = Long.parseLong(decoratorsMatcher.group(GROUP_DECORATORS_TIME_MILLIS));

                if (millisecond < MIN_VALID_UNIX_TIME_MILLIS) {
                    hasUptime = setTimeStampIfPresent(event, String.valueOf(millisecond), "ms") || hasUptime;
                } else {
                    hasTime = setDateStampIfPresent(event, DateHelper.formatDate(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millisecond), ZoneId.systemDefault()))) || hasTime;
                }
            }

            // The second time is the uptime for sure only if the text contains two pairs of nanosecond time
            if (decoratorsMatcher.group(GROUP_DECORATORS_TIME_NANOS) != null && decoratorsMatcher.group(GROUP_DECORATORS_UPTIME_NANOS) != null) {
                hasUptime = setTimeStampIfPresent(event, decoratorsMatcher.group(GROUP_DECORATORS_UPTIME_NANOS), "ns") || hasUptime;
            }

            if (!hasTime && !hasUptime) {
                getLogger().warning(String.format("Failed to parse line number %d (no valid time or timestamp; line=\"%s\")", in.getLineNumber(), line));
                return null;
            }

            return event;
        } else {
            getLogger().warning(String.format("Failed to parse line number %d (no match; line=\"%s\")", in.getLineNumber(), line));
            return null;
        }
    }

    private AbstractGCEvent<?> createGcEvent(ExtendedType type) {
        AbstractGCEvent<?> event;
        if (type.getConcurrency().equals(Concurrency.CONCURRENT)) {
            event = new ConcurrentGCEvent();
        } else if (type.getType().equals(Type.APPLICATION_STOPPED_TIME)) {
            event = new VmOperationEvent();
        } else {
            event = new GCEventUJL();
        }
        return event;
    }

    private void setPause(AbstractGCEvent event, String pauseAsString) {
        // TODO remove code duplication with AbstractDataReaderSun -> move to DataReaderTools
        if (pauseAsString != null && pauseAsString.length() > 0) {
            event.setPause(NumberParser.parseDouble(pauseAsString) / 1000);
        }
    }

    private boolean hasMemory(AbstractGCEvent<?> event) {
        return event.getTotal() > 0;
    }

    private void setMemory(AbstractGCEvent event, Matcher matcher) {
        // TODO remove code duplication with AbstractDataReaderSun -> move to DataReaderTools
        event.setPreUsed(getDataReaderTools().getMemoryInKiloByte(
                Integer.parseInt(matcher.group(GROUP_MEMORY_BEFORE)), matcher.group(GROUP_MEMORY_BEFORE_UNIT).charAt(0), matcher.group(GROUP_MEMORY)));
        event.setPostUsed(getDataReaderTools().getMemoryInKiloByte(
                Integer.parseInt(matcher.group(GROUP_MEMORY_AFTER)), matcher.group(GROUP_MEMORY_AFTER_UNIT).charAt(0), matcher.group(GROUP_MEMORY)));
        event.setTotal(getDataReaderTools().getMemoryInKiloByte(
                Integer.parseInt(matcher.group(GROUP_MEMORY_CURRENT_TOTAL)), matcher.group(GROUP_MEMORY_CURRENT_TOTAL_UNIT).charAt(0), matcher.group(GROUP_MEMORY)));
    }

    private void setMemoryHeapWithPercentage(AbstractGCEvent<?> event, Matcher matcher) {
        event.setTotal(getDataReaderTools().getMemoryInKiloByte(
                Integer.parseInt(matcher.group(GROUP_HEAP_MEMORY_PERCENTAGE_VALUE)), matcher.group(GROUP_HEAP_MEMORY_PERCENTAGE_UNIT).charAt(0), matcher.group(GROUP_HEAP_MEMORY_PERCENTAGE)));
    }

    private void setMemoryWithPercentage(AbstractGCEvent<?> event, Matcher matcher) {
        event.setPreUsed(getDataReaderTools().getMemoryInKiloByte(
                Integer.parseInt(matcher.group(GROUP_MEMORY_PERCENTAGE_BEFORE)), matcher.group(GROUP_MEMORY_PERCENTAGE_BEFORE_UNIT).charAt(0), matcher.group(GROUP_MEMORY_PERCENTAGE)));
        event.setPostUsed(getDataReaderTools().getMemoryInKiloByte(
                Integer.parseInt(matcher.group(GROUP_MEMORY_PERCENTAGE_AFTER)), matcher.group(GROUP_MEMORY_PERCENTAGE_AFTER_UNIT).charAt(0), matcher.group(GROUP_MEMORY_PERCENTAGE)));

        if (event.getTotal() == 0 && Integer.parseInt(matcher.group(GROUP_MEMORY_PERCENTAGE_BEFORE_PERCENTAGE)) != 0) {
            event.setTotal(event.getPostUsed() / Integer.parseInt(matcher.group(GROUP_MEMORY_PERCENTAGE_AFTER_PERCENTAGE)) * 100);
        }
    }

    private boolean setDateStampIfPresent(AbstractGCEvent<?> event, String dateStampAsString) {
        // TODO remove code duplication with AbstractDataReaderSun -> move to DataReaderTools
        if (dateStampAsString != null) {
            event.setDateStamp(DateHelper.parseDate(dateStampAsString));
            return true;
        }
        return false;
    }

    private boolean setTimeStampIfPresent(AbstractGCEvent<?> event, String timeStampAsString, String timeUnit) {
        if (timeStampAsString != null && timeStampAsString.length() > 0) {
            double timestamp = NumberParser.parseDouble(timeStampAsString);
            if ("ms".equals(timeUnit)) {
                timestamp = timestamp / 1000;
            } else if ("ns".equals(timeUnit)) {
                timestamp = timestamp / 1000000000;
            }
            event.setTimestamp(timestamp);
            return true;
        }
        return false;
    }

    private boolean isExcludedLine(String line) {
        return EXCLUDE_STRINGS.stream().anyMatch(line::contains);
    }

    private boolean isCandidateForParseableEvent(String line) {
        return INCLUDE_STRINGS.stream().anyMatch(line::contains);
    }

    private boolean isLogOnlyLine(String line) {
        return LOG_ONLY_STRINGS.stream().anyMatch(line::contains);
    }

    private boolean lineContainsParseableEvent(ParseContext context) {
        if (isCandidateForParseableEvent(context.getLine()) && !isExcludedLine(context.getLine())) {
            if (isLogOnlyLine(context.getLine())) {
                String tail = context.getLine().substring(context.getLine().lastIndexOf("]")+1);
                enrichContext(context, tail);
                getLogger().info(tail);
                return false;
            } else {
                return true;
            }
        }
        return false;
    }

    private void enrichContext(ParseContext context, String tail) {
        Matcher regionSizeMatcher = tail != null ? PATTERN_HEAP_REGION_SIZE.matcher(tail.trim()) : null;
        if (regionSizeMatcher != null && regionSizeMatcher.find()) {
            try {
                context.setRegionSize(Integer.parseInt(regionSizeMatcher.group(GROUP_HEAP_REGION_SIZE)));
            } catch (NumberFormatException e) {
                getLogger().warning(String.format("Failed to parse heap region size on line %d (line=%s)", in.getLineNumber(), context.getLine()));
            }
        }
    }

    private static class ParseContext {
        /** G1 has a region size and logs the gc,heap information with # of regions */
        private static final String REGION_SIZE_KEY = "regionSize";
        private Map<String, AbstractGCEvent<?>> partialEventsMap;
        private Map<String, Object> info;
        private String line;
        private AbstractGCEvent<?> currentEvent;

        public ParseContext(String line, Map<String, AbstractGCEvent<?>> partialEventsMap, Map<String, Object> info) {
            this.line = line;
            this.partialEventsMap = partialEventsMap;
            this.info = info;
        }

        public String getLine() {
            return line;
        }

        public Map<String, AbstractGCEvent<?>> getPartialEventsMap() {
            return partialEventsMap;
        }

        public AbstractGCEvent<?> getCurrentEvent() {
            return currentEvent;
        }

        public void setCurrentEvent(AbstractGCEvent<?> currentEvent) {
            this.currentEvent = currentEvent;
        }

        public int getRegionSize() {
            Object regionSize = this.info.get(REGION_SIZE_KEY);
            return regionSize != null ? (Integer)regionSize : 0;
        }

        public void setRegionSize(int regionSize) {
            this.info.put(REGION_SIZE_KEY, regionSize);
        }

        @Override
        public String toString() {
            return line + (getRegionSize() > 0 ? "; regionsSize=" + getRegionSize() : "") + "; partialEventsMap.size()=" + partialEventsMap.size() + "currentEvent=" + getCurrentEvent();
        }

    }

}
