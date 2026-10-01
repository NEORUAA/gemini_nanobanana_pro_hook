package com.neoruaa.geminihook;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class DexMenuLocatorTest {
    private static final String BUILDER = "Ljava/lang/StringBuilder;";
    private static final int TITLE = 0x7f190123;

    @Test
    public void survivesRenamedClassFieldAndReorderedWideParameters() throws Exception {
        List<List<String>> versions = Arrays.asList(
                Arrays.asList("Ljava/lang/Object;", "Z", "Z"),
                Arrays.asList("J", "Z", "Ljava/lang/String;", "Z", "Z"));
        for (int version = 0; version < versions.size(); version++) {
            String owner = "Lrenamed/Version" + version + ";";
            String field = "randomField" + version;
            List<String> parameters = versions.get(version);
            int argument = parameters.size() - 1;
            DexMenuLocator.Result result = inspect(state(owner, field, parameters, argument, false));
            assertEquals(1, result.chat.size());
            DexMenuLocator.Target target = result.chat.get(0);
            assertEquals(owner, target.owner);
            assertEquals(field, target.field);
            assertEquals(argument, target.visibilityArgument);
            assertEquals(parameters, target.parameters);
        }
    }

    @Test
    public void discoversRenamedFloatyConstructorAndNewTitlePosition() throws Exception {
        String item = "Lshuffled/UnknownMenuItem;";
        List<String> parameters = Arrays.asList("Ljava/lang/Object;", "I", "Z",
                "Ljava/lang/Runnable;", "I");
        ClassDef definition = definition(item, "randomVisible", parameters, 2, false,
                Collections.emptyList());
        ClassDef factory = floatyFactory(item, parameters, 4);
        DexMenuLocator.Result result = inspect(defaultState(), definition, factory);
        assertEquals(1, result.floaty.size());
        DexMenuLocator.Target target = result.floaty.get(0);
        assertEquals(item, target.owner);
        assertEquals(4, target.titleArgument);
        assertEquals(2, target.visibilityArgument);
        assertEquals("randomVisible", target.field);
    }

    @Test
    public void unrelatedConstantFloatyBooleanDoesNotPreventDiscovery() throws Exception {
        String item = "Lchanged/MenuItem;";
        List<String> parameters = Arrays.asList("I", "Ljava/lang/Object;", "Z",
                "Ljava/lang/Runnable;", "I");
        ClassDef base = definition(item, "visible", parameters, 2, false, Collections.emptyList());
        Method constructor = base.getDirectMethods().iterator().next();
        List<Instruction> instructions = new ArrayList<>();
        instructions.add(new ImmutableInstruction11n(Opcode.CONST_4, 2, 0));
        instructions.add(new ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 2, 3,
                new ImmutableFieldReference(item, "unrelatedConstant", "Z")));
        constructor.getImplementation().getInstructions().forEach(instructions::add);
        List<com.android.tools.smali.dexlib2.iface.Field> fields = new ArrayList<>();
        base.getInstanceFields().forEach(fields::add);
        fields.add(new ImmutableField(item, "unrelatedConstant", "Z", Modifier.PUBLIC | Modifier.FINAL,
                null, Collections.emptySet(), Collections.emptySet()));
        ClassDef changed = new ImmutableClassDef(item, base.getAccessFlags(), base.getSuperclass(),
                Collections.emptyList(), null, Collections.emptySet(), fields,
                Collections.singletonList(method(item, "<init>", parameters, "V",
                        constructor.getAccessFlags(), constructor.getImplementation().getRegisterCount(), instructions)));
        DexMenuLocator.Result result = inspect(defaultState(), changed, floatyFactory(item, parameters, 0));
        assertEquals(1, result.floaty.size());
        assertEquals("visible", result.floaty.get(0).field);
    }

    @Test
    public void conflictingFloatyTitlePositionsDoNotPreventChatDiscovery() throws Exception {
        String item = "Lambiguous/MenuItem;";
        List<String> parameters = Arrays.asList("Ljava/lang/Object;", "I", "Z",
                "Ljava/lang/Runnable;", "I");
        DexMenuLocator.Result result = inspect(defaultState(),
                definition(item, "visible", parameters, 2, false, Collections.emptyList()),
                floatyFactory(item, parameters, 1, 4));
        assertEquals(1, result.chat.size());
        assertTrue(result.floaty.isEmpty());
    }

    @Test
    public void conditionalFloatyConstructorDoesNotPreventChatDiscovery() throws Exception {
        String item = "Lconditional/MenuItem;";
        List<String> parameters = Arrays.asList("Ljava/lang/Object;", "I", "Z",
                "Ljava/lang/Runnable;", "I");
        DexMenuLocator.Result result = inspect(defaultState(),
                definition(item, "visible", parameters, 2, true, Collections.emptyList()),
                floatyFactory(item, parameters, 1));
        assertEquals(1, result.chat.size());
        assertTrue(result.floaty.isEmpty());
    }

    @Test(expected = IOException.class)
    public void rejectsLabelsFromDifferentStringBuilders() throws Exception {
        ClassDef base = defaultState();
        List<Method> methods = new ArrayList<>();
        for (Method original : base.getMethods()) {
            if (!original.getName().equals("toString")) { methods.add(original); continue; }
            List<Instruction> instructions = new ArrayList<>();
            int position = 0;
            for (Instruction instruction : original.getImplementation().getInstructions()) {
                instructions.add(instruction);
                if (++position == 4) {
                    instructions.add(new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0,
                            new ImmutableTypeReference(BUILDER)));
                    instructions.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0,
                            new ImmutableMethodReference(BUILDER, "<init>", Collections.emptyList(), "V")));
                }
            }
            methods.add(method(base.getType(), "toString", Collections.emptyList(), "Ljava/lang/String;",
                    Modifier.PUBLIC, 4, instructions));
        }
        List<com.android.tools.smali.dexlib2.iface.Field> fields = new ArrayList<>();
        base.getInstanceFields().forEach(fields::add);
        inspect(new ImmutableClassDef(base.getType(), base.getAccessFlags(), base.getSuperclass(),
                Collections.emptyList(), null, Collections.emptySet(), fields, methods));
    }

    @Test
    public void missingFloatyResourceDoesNotPreventChatDiscovery() throws Exception {
        DexMenuLocator.Result result = DexMenuLocator.inspect(
                new ImmutableDexFile(Opcodes.getDefault(), Collections.singletonList(defaultState())), 0);
        assertEquals(1, result.chat.size());
        assertTrue(result.floaty.isEmpty());
    }

    @Test(expected = IOException.class)
    public void rejectsAmbiguousSemanticStates() throws Exception {
        inspect(defaultState(), state("Lduplicate/State;", "anotherFlag",
                Arrays.asList("Z", "Z"), 1, false));
    }

    @Test(expected = IOException.class)
    public void rejectsConditionalVisibilityConstructor() throws Exception {
        inspect(state("Lconditional/State;", "flag", Arrays.asList("Z", "Z"), 1, true));
    }

    @Test(expected = IOException.class)
    public void doesNotGuessWhenSemanticMarkerIsMissing() throws Exception {
        inspect(simpleClass("Lunrelated/Empty;", Collections.emptyList()));
    }

    @Test
    public void providedHostApkResolvesBothOriginalGatesOffline() throws Exception {
        String path = System.getProperty("gemini.host.apk");
        Assume.assumeTrue("Set GEMINI_HOST_APK to run the host APK regression", path != null);
        DexMenuLocator.Result result = DexMenuLocator.locate(
                Collections.singletonList(new File(path)), 0x7f150556);
        assertEquals(1, result.chat.size());
        assertEquals(14, result.chat.get(0).visibilityArgument);
        assertEquals(29, result.chat.get(0).parameters.size());
        assertEquals(1, result.floaty.size());
        assertEquals(1, result.floaty.get(0).titleArgument);
        assertEquals(2, result.floaty.get(0).visibilityArgument);
    }

    private static DexMenuLocator.Result inspect(ClassDef... classes) throws Exception {
        return DexMenuLocator.inspect(new ImmutableDexFile(Opcodes.getDefault(), Arrays.asList(classes)), TITLE);
    }

    private static ClassDef defaultState() {
        return state("Lfixture/State;", "visibility", Arrays.asList("Z", "Z"), 1, false);
    }

    private static ClassDef floatyFactory(String item, List<String> parameters, int... titlePositions) {
        String owner = "Lshuffled/UnknownFactory;";
        List<Method> methods = new ArrayList<>();
        for (int position : titlePositions) {
            int registers = 1, titleRegister = 1;
            for (int i = 0; i < parameters.size(); i++) {
                if (i < position) titleRegister += wide(parameters.get(i));
                registers += wide(parameters.get(i));
            }
            List<Instruction> instructions = Arrays.asList(
                    new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference(item)),
                    new ImmutableInstruction31i(Opcode.CONST, titleRegister, TITLE),
                    new ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 0, registers,
                            new ImmutableMethodReference(item, "<init>", parameters, "V")),
                    new ImmutableInstruction10x(Opcode.RETURN_VOID));
            methods.add(method(owner, "renamedFactory" + position, Collections.emptyList(), "V",
                    Modifier.PUBLIC | Modifier.STATIC, registers, instructions));
        }
        return simpleClass(owner, methods);
    }

    private static ClassDef state(String owner, String field, List<String> parameters,
            int argument, boolean conditional) {
        ImmutableFieldReference property = new ImmutableFieldReference(owner, field, "Z");
        List<Instruction> instructions = new ArrayList<>();
        // Preload the field before its label, so textual adjacency alone cannot pass this test.
        instructions.add(new ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 2, 3, property));
        instructions.add(new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference(BUILDER)));
        instructions.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 1,
                new ImmutableStringReference("ActionRowState(")));
        instructions.add(invoke("<init>", "Ljava/lang/String;", "V"));
        instructions.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 1,
                new ImmutableStringReference(", showImageRegeneration=")));
        instructions.add(invoke("append", "Ljava/lang/String;", BUILDER));
        instructions.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        instructions.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 2, 0, 0, 0,
                new ImmutableMethodReference(BUILDER, "append", Collections.singletonList("Z"), BUILDER)));
        instructions.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        instructions.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(BUILDER, "toString", Collections.emptyList(), "Ljava/lang/String;")));
        instructions.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        instructions.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        Method toString = method(owner, "toString", Collections.emptyList(), "Ljava/lang/String;",
                Modifier.PUBLIC, 4, instructions);
        return definition(owner, field, parameters, argument, conditional, Collections.singletonList(toString));
    }

    private static ClassDef definition(String owner, String field, List<String> parameters,
            int argument, boolean conditional, List<Method> extraMethods) {
        int register = 4;
        for (int i = 0; i < argument; i++) register += wide(parameters.get(i));
        int registerCount = 4;
        for (String parameter : parameters) registerCount += wide(parameter);
        List<Instruction> instructions = new ArrayList<>();
        if (conditional) instructions.add(new ImmutableInstruction21t(Opcode.IF_EQZ, register, 2));
        instructions.add(new ImmutableInstruction22x(Opcode.MOVE_FROM16, 0, register));
        instructions.add(new ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 3));
        instructions.add(new ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 0, 1,
                new ImmutableFieldReference(owner, field, "Z")));
        instructions.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        List<Method> methods = new ArrayList<>(extraMethods);
        methods.add(method(owner, "<init>", parameters, "V", Modifier.PUBLIC | 0x10000,
                registerCount, instructions));
        ImmutableField property = new ImmutableField(owner, field, "Z", Modifier.PUBLIC | Modifier.FINAL,
                null, Collections.emptySet(), Collections.emptySet());
        return new ImmutableClassDef(owner, Modifier.PUBLIC | Modifier.FINAL, "Ljava/lang/Object;",
                Collections.emptyList(), null, Collections.emptySet(), Collections.singletonList(property), methods);
    }

    private static int wide(String type) { return type.equals("J") || type.equals("D") ? 2 : 1; }

    private static Instruction invoke(String name, String parameter, String result) {
        return new ImmutableInstruction35c(name.equals("<init>") ? Opcode.INVOKE_DIRECT : Opcode.INVOKE_VIRTUAL,
                2, 0, 1, 0, 0, 0,
                new ImmutableMethodReference(BUILDER, name, Collections.singletonList(parameter), result));
    }

    private static ClassDef simpleClass(String owner, List<Method> methods) {
        return new ImmutableClassDef(owner, Modifier.PUBLIC, "Ljava/lang/Object;", Collections.emptyList(),
                null, Collections.emptySet(), Collections.emptyList(), methods);
    }

    private static Method method(String owner, String name, List<String> parameters, String result,
            int access, int registers, List<Instruction> instructions) {
        List<ImmutableMethodParameter> args = new ArrayList<>();
        for (String type : parameters) args.add(new ImmutableMethodParameter(type, Collections.emptySet(), null));
        return new ImmutableMethod(owner, name, args, result, access, Collections.emptySet(),
                Collections.emptySet(), new ImmutableMethodImplementation(registers, instructions,
                Collections.emptyList(), Collections.emptyList()));
    }
}
