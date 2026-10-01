package com.neoruaa.geminihook;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves menu gates from DEX semantics, without a host-version/name table. */
final class DexMenuLocator {
    static final String FLOATY_TITLE_RESOURCE =
            "assistant_floaty_message_content_actions_options_view_nano_banana_pro_title";
    private static final String STATE_LABEL = "ActionRowState(";
    private static final String VISIBILITY_LABEL = "showImageRegeneration=";
    private static final String BUILDER = "Ljava/lang/StringBuilder;";
    private static final Object THIS = new Object();

    static final class Target {
        final String owner;
        final List<String> parameters;
        final String field;
        final int visibilityArgument;
        final int titleArgument;

        Target(MethodReference constructor, String field, int visibility, int title) {
            this(constructor.getDefiningClass(), strings(constructor.getParameterTypes()),
                    field, visibility, title);
        }

        Target(String owner, List<String> parameters, String field, int visibility, int title) {
            this.owner = owner;
            this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
            this.field = field;
            this.visibilityArgument = visibility;
            this.titleArgument = title;
        }

        String key() {
            return owner + parameters + ":" + field + ":" + visibilityArgument + ":" + titleArgument;
        }

        Constructor<?> resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?>[] types = new Class<?>[parameters.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = resolveType(parameters.get(i), loader);
            }
            return resolveType(owner, loader).getDeclaredConstructor(types);
        }
    }

    static final class Result {
        final List<Target> chat = new ArrayList<>();
        final List<Target> floaty = new ArrayList<>();

        void validate() throws IOException {
            Set<String> owners = new LinkedHashSet<>();
            for (Target target : chat) {
                owners.add(target.owner + "->" + target.field);
            }
            if (owners.size() != 1 || chat.isEmpty()) {
                throw new IOException("ActionRowState visibility is missing or ambiguous");
            }
        }
    }

    private DexMenuLocator() {}

    static Result locate(List<File> apks, int floatyTitle) throws IOException {
        Result result = new Result();
        Map<String, Target> floatyCalls = new LinkedHashMap<>();
        byte[] stateMarker = VISIBILITY_LABEL.getBytes(StandardCharsets.UTF_8);
        byte[] resourceMarker = FLOATY_TITLE_RESOURCE.getBytes(StandardCharsets.UTF_8);
        byte[] titleBytes = {(byte) floatyTitle, (byte) (floatyTitle >>> 8),
                (byte) (floatyTitle >>> 16), (byte) (floatyTitle >>> 24)};

        // Parse one DEX at a time; most Google DEX files contain neither anchor.
        for (File apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(entry)) {
                        bytes = readBytes(input);
                    }
                    boolean state = contains(bytes, stateMarker);
                    boolean floaty = floatyTitle != 0 && (contains(bytes, titleBytes)
                            || contains(bytes, resourceMarker)
                            || ((floatyTitle & 0xffff) == 0
                            && contains(bytes, Arrays.copyOfRange(titleBytes, 2, 4))));
                    if (!state && !floaty) continue;
                    DexFile dex = new DexBackedDexFile(null, bytes);
                    inspect(dex, floatyTitle, state, floaty, result, floatyCalls);
                    verifyFloaty(dex, floatyCalls, result);
                }
            }
        }
        finishFloaty(apks, floatyCalls, result);
        removeAmbiguousFloaty(floatyCalls, result);
        deduplicate(result.chat);
        deduplicate(result.floaty);
        result.validate();
        return result;
    }

    static Result inspect(DexFile dex, int floatyTitle) throws IOException {
        Result result = new Result();
        Map<String, Target> calls = new LinkedHashMap<>();
        inspect(dex, floatyTitle, true, floatyTitle != 0, result, calls);
        verifyFloaty(dex, calls, result);
        removeAmbiguousFloaty(calls, result);
        deduplicate(result.chat);
        deduplicate(result.floaty);
        result.validate();
        return result;
    }

    private static void inspect(DexFile dex, int title, boolean searchState,
            boolean searchFloaty, Result result, Map<String, Target> calls) throws IOException {
        for (ClassDef owner : dex.getClasses()) {
            for (Method method : owner.getMethods()) {
                if (searchState && method.getName().equals("toString")
                        && (method.getAccessFlags() & 0x8) == 0
                        && method.getParameterTypes().isEmpty()
                        && method.getReturnType().equals("Ljava/lang/String;")) {
                    String field = visibilityField(method);
                    if (field != null) {
                        int before = result.chat.size();
                        for (Method constructor : owner.getDirectMethods()) {
                            if (constructor.getName().equals("<init>")) {
                                int argument = constructorArgument(constructor, field);
                                if (argument >= 0) {
                                    result.chat.add(new Target(constructor, field, argument, -1));
                                }
                            }
                        }
                        if (result.chat.size() == before) {
                            throw new IOException("Visibility field has no direct boolean constructor input");
                        }
                    }
                }
                if (searchFloaty && hasTitle(method, title)) {
                    floatyConstructors(method, title, calls);
                }
            }
        }
    }

    private static String visibilityField(Method method) throws IOException {
        MethodImplementation code = method.getImplementation();
        if (code == null) return null;
        Object[] registers = new Object[code.getRegisterCount()];
        registers[registers.length - 1] = THIS;
        Object pending = null;
        Set<String> fields = new LinkedHashSet<>();
        for (Instruction instruction : code.getInstructions()) {
            // Do not infer a field from one branch of a conditional description.
            if (isBranch(instruction)) return null;
            Object reference = reference(instruction);
            if (instruction.getOpcode() == Opcode.IGET_BOOLEAN) {
                TwoRegisterInstruction access = (TwoRegisterInstruction) instruction;
                FieldReference field = (FieldReference) reference;
                registers[access.getRegisterA()] = registers[access.getRegisterB()] == THIS
                        && field.getDefiningClass().equals(method.getDefiningClass()) ? field : null;
            } else if (instruction.getOpcode() == Opcode.NEW_INSTANCE
                    && ((TypeReference) reference).getType().equals(BUILDER)) {
                registers[((OneRegisterInstruction) instruction).getRegisterA()] = new BuilderState();
            } else if (reference instanceof MethodReference && isInvoke(instruction)) {
                MethodReference call = (MethodReference) reference;
                int[] args = invokeRegisters(instruction);
                pending = null;
                if (call.getDefiningClass().equals(BUILDER) && args.length == 2
                        && registers[args[0]] instanceof BuilderState) {
                    BuilderState builder = (BuilderState) registers[args[0]];
                    Object value = registers[args[1]];
                    if (call.getName().equals("<init>") || call.getName().equals("append")) {
                        if (value instanceof String) {
                            builder.label = (String) value;
                            builder.stateLabelSeen |= builder.label.contains(STATE_LABEL);
                        } else {
                            if (builder.stateLabelSeen && builder.label != null
                                    && builder.label.endsWith(VISIBILITY_LABEL)
                                    && value instanceof FieldReference
                                    && call.getParameterTypes().size() == 1
                                    && call.getParameterTypes().get(0).toString().equals("Z")) {
                                fields.add(((FieldReference) value).getName());
                            }
                            builder.label = null;
                        }
                        if (call.getName().equals("append")) pending = builder;
                    }
                }
            } else {
                track(instruction, registers, pending);
            }
        }
        if (fields.isEmpty()) return null;
        if (fields.size() != 1) throw new IOException("Ambiguous showImageRegeneration field");
        return fields.iterator().next();
    }

    static int constructorArgument(Method method, String fieldName) throws IOException {
        MethodImplementation code = method.getImplementation();
        if (code == null) return -1;
        List<? extends CharSequence> types = method.getParameterTypes();
        int parameterWords = 1;
        for (CharSequence type : types) parameterWords += words(type.toString());
        Object[] registers = new Object[code.getRegisterCount()];
        int cursor = registers.length - parameterWords;
        registers[cursor++] = THIS;
        for (int i = 0; i < types.size(); i++) {
            registers[cursor] = new Parameter(i);
            cursor += words(types.get(i).toString());
        }
        Set<Integer> arguments = new LinkedHashSet<>();
        boolean branches = false;
        for (Instruction instruction : code.getInstructions()) {
            branches |= isBranch(instruction);
            Object reference = reference(instruction);
            if (instruction.getOpcode() == Opcode.IPUT_BOOLEAN && reference instanceof FieldReference) {
                FieldReference field = (FieldReference) reference;
                if (field.getDefiningClass().equals(method.getDefiningClass())
                        && field.getName().equals(fieldName)) {
                    TwoRegisterInstruction store = (TwoRegisterInstruction) instruction;
                    Object value = registers[store.getRegisterA()];
                    if (registers[store.getRegisterB()] != THIS || !(value instanceof Parameter)
                            || !types.get(((Parameter) value).index).toString().equals("Z")) {
                        throw new IOException("Visibility is not a direct boolean parameter");
                    }
                    arguments.add(((Parameter) value).index);
                }
            }
            track(instruction, registers, null);
        }
        if (!arguments.isEmpty() && branches) {
            throw new IOException("Conditional visibility constructor requires a new semantic rule");
        }
        if (arguments.size() > 1) throw new IOException("Ambiguous visibility constructor parameter");
        return arguments.isEmpty() ? -1 : arguments.iterator().next();
    }

    private static boolean hasTitle(Method method, int title) {
        if (method.getImplementation() == null) return false;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof NarrowLiteralInstruction
                    && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == title) return true;
            if (isTitleField(reference(instruction))) return true;
        }
        return false;
    }

    private static void floatyConstructors(Method method, int title, Map<String, Target> targets) {
        MethodImplementation code = method.getImplementation();
        Object[] registers = new Object[code.getRegisterCount()];
        for (Instruction instruction : code.getInstructions()) {
            Object reference = reference(instruction);
            if ((instruction.getOpcode() == Opcode.INVOKE_DIRECT
                    || instruction.getOpcode() == Opcode.INVOKE_DIRECT_RANGE)
                    && reference instanceof MethodReference) {
                MethodReference call = (MethodReference) reference;
                if (call.getName().equals("<init>")) {
                    List<? extends CharSequence> types = call.getParameterTypes();
                    int[] args = invokeRegisters(instruction);
                    int visibility = -1, titleArgument = -1, booleans = 0, objects = 0, integers = 0;
                    int cursor = 1;
                    for (int i = 0; i < types.size(); i++) {
                        String type = types.get(i).toString();
                        if (type.equals("Z")) { visibility = i; booleans++; }
                        if (type.startsWith("L") || type.startsWith("[")) objects++;
                        if (type.equals("I")) {
                            integers++;
                            if (Integer.valueOf(title).equals(registers[args[cursor]])) {
                                titleArgument = titleArgument == -1 ? i : -2;
                            }
                        }
                        cursor += words(type);
                    }
                    if (booleans == 1 && objects >= 2 && integers >= 2 && titleArgument >= 0) {
                        Target target = new Target(call, "", visibility, titleArgument);
                        targets.put(target.key(), target);
                    }
                }
            }
            if (instruction.getOpcode() == Opcode.SGET && isTitleField(reference)) {
                registers[((OneRegisterInstruction) instruction).getRegisterA()] = title;
            } else {
                track(instruction, registers, null);
            }
        }
    }

    private static void finishFloaty(List<File> apks, Map<String, Target> calls, Result result)
            throws IOException {
        if (calls.isEmpty()) return;
        // Definitions can live in a different DEX or split from the menu-building call site.
        Set<String> pending = new LinkedHashSet<>();
        for (Target target : calls.values()) {
            boolean resolved = false;
            for (Target found : result.floaty) {
                if (found.owner.equals(target.owner) && found.parameters.equals(target.parameters)) {
                    resolved = true;
                }
            }
            if (!resolved) pending.add(target.owner);
        }
        if (pending.isEmpty()) return;
        for (File apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements() && !pending.isEmpty()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(entry)) { bytes = readBytes(input); }
                    boolean relevant = false;
                    for (String owner : pending) {
                        if (contains(bytes, owner.getBytes(StandardCharsets.UTF_8))) relevant = true;
                    }
                    if (!relevant) continue;
                    DexFile dex = new DexBackedDexFile(null, bytes);
                    verifyFloaty(dex, calls, result);
                    for (ClassDef owner : dex.getClasses()) pending.remove(owner.getType());
                }
            }
        }
    }

    private static void verifyFloaty(DexFile dex, Map<String, Target> calls, Result result)
            throws IOException {
        for (ClassDef owner : dex.getClasses()) {
            for (Target target : calls.values()) {
                if (!target.owner.equals(owner.getType())) continue;
                for (Method method : owner.getDirectMethods()) {
                    if (!method.getName().equals("<init>")
                            || !strings(method.getParameterTypes()).equals(target.parameters)) continue;
                    Set<String> visibilityFields = new LinkedHashSet<>();
                    for (com.android.tools.smali.dexlib2.iface.Field field : owner.getInstanceFields()) {
                        if (!field.getType().equals("Z") || (field.getAccessFlags() & 0x10) == 0) continue;
                        try {
                            if (constructorArgument(method, field.getName()) == target.visibilityArgument) {
                                visibilityFields.add(field.getName());
                            }
                        } catch (IOException unsupported) {
                            // An unrelated constant/transformed field is not a visibility candidate.
                        }
                    }
                    if (visibilityFields.size() == 1) {
                        result.floaty.add(new Target(method, visibilityFields.iterator().next(),
                                target.visibilityArgument, target.titleArgument));
                    }
                }
            }
        }
    }

    private static void removeAmbiguousFloaty(Map<String, Target> calls, Result result) {
        // Conflicting call sites must not turn a logging ID into a title guard.
        result.floaty.removeIf(found -> {
            for (Target call : calls.values()) {
                if (call.owner.equals(found.owner) && call.parameters.equals(found.parameters)
                        && call.titleArgument != found.titleArgument) return true;
            }
            return false;
        });
    }

    private static void track(Instruction instruction, Object[] registers, Object pending) {
        Opcode opcode = instruction.getOpcode();
        if (opcode == Opcode.MOVE || opcode == Opcode.MOVE_FROM16 || opcode == Opcode.MOVE_16
                || opcode == Opcode.MOVE_OBJECT || opcode == Opcode.MOVE_OBJECT_FROM16
                || opcode == Opcode.MOVE_OBJECT_16) {
            TwoRegisterInstruction move = (TwoRegisterInstruction) instruction;
            registers[move.getRegisterA()] = registers[move.getRegisterB()];
        } else if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
            registers[((OneRegisterInstruction) instruction).getRegisterA()] =
                    ((StringReference) reference(instruction)).getString();
        } else if (opcode == Opcode.CONST || opcode == Opcode.CONST_4 || opcode == Opcode.CONST_16
                || opcode == Opcode.CONST_HIGH16) {
            registers[((OneRegisterInstruction) instruction).getRegisterA()] =
                    ((NarrowLiteralInstruction) instruction).getNarrowLiteral();
        } else if (opcode == Opcode.MOVE_RESULT_OBJECT) {
            registers[((OneRegisterInstruction) instruction).getRegisterA()] = pending;
        } else if (opcode.setsRegister() && instruction instanceof OneRegisterInstruction) {
            int destination = ((OneRegisterInstruction) instruction).getRegisterA();
            registers[destination] = null;
            if (opcode.setsWideRegister()) registers[destination + 1] = null;
        }
    }

    private static Object reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction
                ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    private static boolean isTitleField(Object reference) {
        return reference instanceof FieldReference
                && ((FieldReference) reference).getType().equals("I")
                && ((FieldReference) reference).getName().equals(FLOATY_TITLE_RESOURCE);
    }

    private static boolean isInvoke(Instruction instruction) {
        return instruction.getOpcode().name.startsWith("invoke-");
    }

    private static boolean isBranch(Instruction instruction) {
        String name = instruction.getOpcode().name;
        return name.startsWith("if-") || name.startsWith("goto") || name.endsWith("switch");
    }

    private static int[] invokeRegisters(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction range = (RegisterRangeInstruction) instruction;
            int[] args = new int[range.getRegisterCount()];
            for (int i = 0; i < args.length; i++) args[i] = range.getStartRegister() + i;
            return args;
        }
        FiveRegisterInstruction invoke = (FiveRegisterInstruction) instruction;
        return Arrays.copyOf(new int[]{invoke.getRegisterC(), invoke.getRegisterD(),
                invoke.getRegisterE(), invoke.getRegisterF(), invoke.getRegisterG()},
                invoke.getRegisterCount());
    }

    private static int words(String type) { return type.equals("J") || type.equals("D") ? 2 : 1; }

    private static List<String> strings(List<? extends CharSequence> values) {
        List<String> result = new ArrayList<>();
        for (CharSequence value : values) result.add(value.toString());
        return result;
    }

    private static void deduplicate(List<Target> targets) {
        Map<String, Target> unique = new LinkedHashMap<>();
        for (Target target : targets) unique.put(target.key(), target);
        targets.clear();
        targets.addAll(unique.values());
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (haystack[i] != needle[0]) continue;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static Class<?> resolveType(String type, ClassLoader loader) throws ClassNotFoundException {
        switch (type) {
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "C": return char.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
            default:
                String name = type.startsWith("L") ? type.substring(1, type.length() - 1) : type;
                return Class.forName(name.replace('/', '.'), false, loader);
        }
    }

    private static final class BuilderState {
        String label;
        boolean stateLabelSeen;
    }
    private static final class Parameter {
        final int index;
        Parameter(int index) { this.index = index; }
    }
}
