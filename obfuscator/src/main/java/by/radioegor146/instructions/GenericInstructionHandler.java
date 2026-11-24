package by.radioegor146.instructions;

import by.radioegor146.CatchesBlock;
import by.radioegor146.MethodContext;
import by.radioegor146.MethodProcessor;
import by.radioegor146.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.*;
import java.util.stream.Collectors;

public abstract class GenericInstructionHandler<T extends AbstractInsnNode> implements InstructionTypeHandler<T> {

    private static final boolean TRACE_LABEL = Boolean.getBoolean("nob.trace.label");
    private static final Set<String> TRACE_METHODS = parseTraceMethods();

    private static Set<String> parseTraceMethods() {
        String value = System.getProperty("nob.trace.methods");
        if (value == null || value.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(Arrays.asList(value.split(",")));
    }

    protected Map<String, String> props;
    protected String instructionName;
    protected String trimmedTryCatchBlock;

    @Override
    public void accept(MethodContext context, T node) {
        props = new HashMap<>();
        List<TryCatchBlockNode> tryCatchBlockNodeList = new ArrayList<>();
        for (TryCatchBlockNode tryCatchBlock : context.method.tryCatchBlocks) {
            if (!context.tryCatches.contains(tryCatchBlock)) {
                continue;
            }
            if (tryCatchBlockNodeList.stream().noneMatch(tryCatchBlockNode ->
                    Objects.equals(tryCatchBlockNode.type, tryCatchBlock.type))) {
                tryCatchBlockNodeList.add(tryCatchBlock);
            }
        }
        instructionName = MethodProcessor.INSTRUCTIONS.getOrDefault(node.getOpcode(), "NOTFOUND");
        props.put("line", String.valueOf(context.line));
        StringBuilder tryCatch = new StringBuilder("\n");
        tryCatch.append("    ");
        if (tryCatchBlockNodeList.size() > 0) {
            String tryCatchLabelName = context.catches.computeIfAbsent(new CatchesBlock(tryCatchBlockNodeList.stream().map(item ->
                    new CatchesBlock.CatchBlock(item.type, item.handler)).collect(Collectors.toList())),
                    key -> String.format("L_CATCH_%d", context.catches.size()));
            tryCatch.append(context.getSnippets().getSnippet("TRYCATCH_START"));
            tryCatch.append(" goto ").append(tryCatchLabelName).append("; }");
        } else {
            tryCatch.append(context.getSnippets().getSnippet("TRYCATCH_EMPTY", Util.createMap(
                    "rettype", MethodProcessor.CPP_TYPES[context.ret.getSort()]
            )));
        }
        props.put("trycatchhandler", tryCatch.toString());
        props.put("rettype", MethodProcessor.CPP_TYPES[context.ret.getSort()]);
        trimmedTryCatchBlock = tryCatch.toString().trim().replace('\n', ' ');

        for (int i = -5; i <= 5; i++) {
            props.put("stackindex" + (i >= 0 ? i : "m" + (-i)), String.valueOf(context.stackPointer + i));
        }

        context.output.append("    ");
        process(context, node);

        FieldInsnNode traceField = (node instanceof FieldInsnNode) ? (FieldInsnNode) node : null;
        boolean traceLabelField = TRACE_LABEL && traceField != null
                && "label".equals(traceField.name) && "I".equals(traceField.desc);
        boolean traceArith = TRACE_METHODS.contains(context.method.name)
                && (node.getOpcode() == Opcodes.IADD || node.getOpcode() == Opcodes.ISUB
                || node.getOpcode() == Opcodes.IMUL || node.getOpcode() == Opcodes.IAND);
        int valueSlot = context.stackPointer - 1;

        if (traceLabelField && node.getOpcode() == Opcodes.GETFIELD) {
            context.output.append(String.format("{ const void* __nob_obj = (void*) cstack%d.l; ", valueSlot));
        }

        if (instructionName != null) {
            context.output.append(context.obfuscator.getSnippets().getSnippet(instructionName, props));
        }

        // Opt-in coroutine diagnostics (-Dnob.trace.label=true): every read and
        // write of a field named "label" is logged, which is the state word of
        // a Kotlin continuation state machine. Off by default so normal builds
        // are byte-for-byte unaffected. NOB_TRACE routes to logcat on Android
        // (native stderr goes to /dev/null there).
        if (traceLabelField) {
            if (node.getOpcode() == Opcodes.GETFIELD) {
                context.output.append(String.format(
                        " NOB_TRACE(\"[nob] %%s GET label -> %%d on %%p\\n\", \"%s\", cstack%d.i, __nob_obj); }",
                        context.method.name, valueSlot));
            } else if (node.getOpcode() == Opcodes.PUTFIELD) {
                context.output.append(String.format(
                        " NOB_TRACE(\"[nob] %%s PUT label = %%d on %%p\\n\", \"%s\", cstack%d.i, (void*) cstack%d.l);",
                        context.method.name, valueSlot, context.stackPointer - 2));
            }
        }
        if (traceArith) {
            context.output.append(String.format(
                    " NOB_TRACE(\"[nob] %%s %s -> %%d\\n\", \"%s\", cstack%d.i);",
                    instructionName, context.method.name, context.stackPointer - 2));
        }
        context.output.append("\n");
    }

    protected abstract void process(MethodContext context, T node);
}
