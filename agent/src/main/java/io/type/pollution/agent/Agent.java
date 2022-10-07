package io.type.pollution.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class Agent {

    private static final boolean ENABLE_STATISTICS_CLEANUP =  Boolean.getBoolean("io.type.pollution.cleanup");
    private static final boolean ENABLE_FULL_STACK_TRACES =  Boolean.getBoolean("io.type.pollution.full.traces");

    private static final Long REPORT_INTERVAL = Long.getLong("io.type.pollution.report.interval");

    public static void premain(String agentArgs, Instrumentation inst) {
        if (ENABLE_FULL_STACK_TRACES) {
            TraceInstanceOf.startMetronome();
        }

        if (REPORT_INTERVAL != null) {
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("type-pollution-periodic-report");
                return t;
            }).scheduleWithFixedDelay(Agent::printReport, REPORT_INTERVAL, REPORT_INTERVAL, TimeUnit.SECONDS);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(Agent::printReport));

        final String[] agentArgsValues = agentArgs == null ? null : agentArgs.split(",");
        ElementMatcher.Junction<? super TypeDescription> acceptedTypes = any();
        if (agentArgsValues != null && agentArgsValues.length > 0) {
            for (String startWith : agentArgsValues)
                acceptedTypes = acceptedTypes.and(nameStartsWith(startWith));
        }
        new AgentBuilder.Default()
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(acceptedTypes
                        .and(not(nameStartsWith("net.bytebuddy.")))
                        .and(not(nameStartsWith("com.sun")))
                        .and(not(nameStartsWith("io.type.pollution.agent"))))
                .transform((builder,
                            typeDescription,
                            classLoader,
                            module,
                            protectionDomain) -> builder.visit(new AsmVisitorWrapper.AbstractBase() {

                    @Override
                    public int mergeWriter(int flags) {
                        return flags | ClassWriter.COMPUTE_FRAMES;
                    }

                    @Override
                    public int mergeReader(int flags) {
                        return flags;
                    }

                    @Override
                    public net.bytebuddy.jar.asm.ClassVisitor wrap(TypeDescription instrumentedType,
                                                                   net.bytebuddy.jar.asm.ClassVisitor classVisitor,
                                                                   Implementation.Context implementationContext,
                                                                   TypePool typePool,
                                                                   FieldList<FieldDescription.InDefinedShape> fields,
                                                                   MethodList<?> methods,
                                                                   int writerFlags, int readerFlags) {
                        return new ByteBuddyUtils.ByteBuddyTypePollutionClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, classVisitor);
                    }
                })).installOn(inst);
    }

    private static void printReport() {
        System.out.println("--------------------------\nType Pollution Statistics:");
        class MutableInt {
            int rowId = 0;
        }
        MutableInt mutableInt = new MutableInt();
        TraceInstanceOf.orderedSnapshot(ENABLE_STATISTICS_CLEANUP).forEach(snapshot -> {
            mutableInt.rowId++;
            System.out.println("--------------------------\n" + mutableInt.rowId + ":\t" + snapshot.clazz.getName());
            System.out.println("Count:\t" + snapshot.updateCount + "\nTypes:");
            for (Class<?> seen : snapshot.seen) {
                System.out.println("\t" + seen.getName());
            }
            System.out.println("Traces:");
            for (String stack : snapshot.topStackTraces) {
                System.out.println("\t" + stack);
            }
            if (ENABLE_FULL_STACK_TRACES) {
                System.out.println("Full Traces:");
                int i = 1;
                for (StackTraceElement[] fullFrames : snapshot.fullStackFrames) {
                    System.out.println("\t--------------------------");
                    i++;
                    for (StackTraceElement frame : fullFrames) {
                        System.out.println("\t" + frame);
                    }
                }
            }
        });
        if (mutableInt.rowId > 0) {
            System.out.println("--------------------------\n");
        }
    }
}

