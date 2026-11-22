package darktext;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;

/**
 * ASM Transformer that:
 * 1. Patches FontRenderer.drawString() to call DarkTextHelper.fixColor()
 * 2. Patches GuiContainer.drawScreen() to set the inContainerGui flag
 * ONLY around the call to drawGuiContainerForegroundLayer(),
 * avoiding item durability/stack count text.
 */
public class DarkTextTransformer implements IClassTransformer {

    private static final String FONT_RENDERER = "net.minecraft.client.gui.FontRenderer";
    private static final String GUI_CONTAINER = "net.minecraft.client.gui.inventory.GuiContainer";
    private static final String RENDER_ITEM = "net.minecraft.client.renderer.entity.RenderItem";
    private static final String MODULAR_UI_SCREEN_HANDLER = "com.cleanroommc.modularui.screen.ClientScreenHandler";
    private static final String MODULAR_UI_TEXT_RENDERER = "com.cleanroommc.modularui.drawable.text.TextRenderer";
    private static final String BATCHING_FONT_RENDERER = "com.gtnewhorizons.angelica.client.font.BatchingFontRenderer";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null)
            return null;

        if (transformedName.equals(FONT_RENDERER)) {
            return transformFontRenderer(basicClass);
        }

        if (transformedName.equals(GUI_CONTAINER)) {
            return transformGuiContainer(basicClass);
        }

        if (transformedName.equals(MODULAR_UI_SCREEN_HANDLER)) {
            return transformModularUiScreenHandler(basicClass);
        }

        if (transformedName.equals(MODULAR_UI_TEXT_RENDERER)) {
            return transformModularUiTextRenderer(basicClass);
        }

        if (transformedName.equals(BATCHING_FONT_RENDERER)) {
            return transformBatchingFontRenderer(basicClass);
        }

        if (transformedName.equals(RENDER_ITEM)) {
            return transformRenderItem(basicClass);
        }

        return basicClass;
    }

    // ===== FontRenderer transformation =====

    private byte[] transformFontRenderer(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming FontRenderer...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                if (method.name.equals("drawString") ||
                        method.name.equals("a") ||
                        method.name.equals("func_78276_b") ||
                        method.name.equals("func_85187_a")) {
                    patchDrawString(method);
                }

                // Colors embedded in the text as §-formatting codes bypass the
                // color parameter and are read straight from the colorCode table.
                if ((method.name.equals("renderStringAtPos") || method.name.equals("func_78255_a"))
                        && method.desc.equals("(Ljava/lang/String;Z)V")) {
                    patchFormattingCodeColors(method, "colorCode", "field_78285_g");
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] FontRenderer transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming FontRenderer: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    private void patchDrawString(MethodNode method) {
        String desc = method.desc;

        // 1.7.10 has three color-carrying overloads:
        //   drawString(String, int, int, int)
        //   drawString(String, int, int, int, boolean)   <- used directly by ModularUI2
        //   drawString(String, float, float, int, boolean) (older/variant mapping)
        if (desc.equals("(Ljava/lang/String;III)I")
                || desc.equals("(Ljava/lang/String;IIIZ)I")
                || desc.equals("(Ljava/lang/String;FFIZ)I")) {
            patchColorParam(method, 4);
        }
    }

    /** Injects fixColor() on a color parameter at the given local variable slot. */
    private void patchColorParam(MethodNode method, int colorParamIndex) {
        System.out.println("[DarkTextFix]   Patching: " + method.name + method.desc);

        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ILOAD, colorParamIndex));
        inject.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "darktext/DarkTextHelper",
                "fixColor",
                "(I)I",
                false));
        inject.add(new VarInsnNode(Opcodes.ISTORE, colorParamIndex));

        method.instructions.insert(inject);
    }

    /**
     * Applies fixColor() to colors read from a {@code colorCode[]} table. Used for
     * §-formatting codes embedded in strings (e.g. text styled with
     * EnumChatFormatting.DARK_GRAY), which never reach the drawString color parameter.
     *
     * <p>Skips lookups that add an offset before the IALOAD (colorCode[index + 16]),
     * since those are shadow colors rather than the text color.
     */
    private void patchFormattingCodeColors(MethodNode method, String... fieldNames) {
        int patchCount = 0;
        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();

        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();
            if (!(node instanceof FieldInsnNode))
                continue;

            FieldInsnNode field = (FieldInsnNode) node;
            if (field.getOpcode() != Opcodes.GETFIELD || !field.desc.equals("[I"))
                continue;

            boolean nameMatch = false;
            for (String name : fieldNames) {
                if (field.name.equals(name)) {
                    nameMatch = true;
                    break;
                }
            }
            if (!nameMatch)
                continue;

            AbstractInsnNode cursor = field.getNext();
            boolean offsetLookup = false;
            while (cursor != null && cursor.getOpcode() == -1)
                cursor = cursor.getNext();

            while (cursor != null && cursor.getOpcode() != Opcodes.IALOAD) {
                int opcode = cursor.getOpcode();
                if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB || opcode == Opcodes.IMUL
                        || opcode == Opcodes.IDIV) {
                    offsetLookup = true;
                }
                cursor = cursor.getNext();
            }

            if (cursor == null || offsetLookup)
                continue;

            method.instructions.insert(cursor, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "darktext/DarkTextHelper",
                    "fixColor",
                    "(I)I",
                    false));
            patchCount++;
        }

        System.out.println("[DarkTextFix]   Patched " + patchCount + " formatting color(s) in " + method.name + method.desc);
    }

    // ===== GuiContainer transformation =====

    private byte[] transformGuiContainer(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming GuiContainer...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                // drawScreen(int mouseX, int mouseY, float partialTicks)
                if ((method.name.equals("drawScreen") || method.name.equals("func_73863_a"))
                        && method.desc.equals("(IIF)V")) {
                    patchDrawScreen(method);
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] GuiContainer transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming GuiContainer: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    /**
     * Patches drawScreen() to wrap ONLY the call to
     * drawGuiContainerForegroundLayer()
     * with the inContainerGui flag. This way:
     * - Title text (inside foreground layer) -> flag is TRUE -> colors get fixed
     * - Item durability/stack count text -> flag is FALSE -> colors are untouched
     * - Tooltips -> flag is FALSE -> colors are untouched
     */
    private void patchDrawScreen(MethodNode method) {
        System.out.println("[DarkTextFix]   Patching GuiContainer.drawScreen() - wrapping foreground layer call");

        boolean found = false;
        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();

        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();

            // Look for: INVOKEVIRTUAL GuiContainer.drawGuiContainerForegroundLayer(II)V
            if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node instanceof MethodInsnNode) {
                MethodInsnNode methodCall = (MethodInsnNode) node;

                if ((methodCall.name.equals("drawGuiContainerForegroundLayer") ||
                        methodCall.name.equals("func_146979_b"))
                        && methodCall.desc.equals("(II)V")) {

                    System.out.println(
                            "[DarkTextFix]     Found call to " + methodCall.name + " - injecting flag wrapper");

                    // INSERT BEFORE the call: DarkTextHelper.inContainerGui = true;
                    InsnList setFlag = new InsnList();
                    setFlag.add(new InsnNode(Opcodes.ICONST_1));
                    setFlag.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "darktext/DarkTextHelper",
                            "inContainerGui",
                            "Z"));
                    // Insert before the ALOAD that pushes 'this' for the method call.
                    // We need to go back to find the start of the call sequence.
                    // The call sequence is: ALOAD 0, ILOAD x, ILOAD y, INVOKEVIRTUAL
                    // We insert the flag set BEFORE the ALOAD 0.
                    // But it's simpler to insert right before the INVOKEVIRTUAL;
                    // the flag only matters when drawString is called inside the method.
                    method.instructions.insertBefore(node, setFlag);

                    // INSERT AFTER the call: DarkTextHelper.inContainerGui = false;
                    InsnList resetFlag = new InsnList();
                    resetFlag.add(new InsnNode(Opcodes.ICONST_0));
                    resetFlag.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "darktext/DarkTextHelper",
                            "inContainerGui",
                            "Z"));
                    method.instructions.insert(node, resetFlag);

                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            System.err.println(
                    "[DarkTextFix]   WARNING: Could not find drawGuiContainerForegroundLayer call in drawScreen!");
            System.out.println("[DarkTextFix]   Falling back: dumping all method calls for debugging...");
            iter = method.instructions.iterator();
            while (iter.hasNext()) {
                AbstractInsnNode node = iter.next();
                if (node instanceof MethodInsnNode) {
                    MethodInsnNode m = (MethodInsnNode) node;
                    System.out.println("[DarkTextFix]     CALL: " + m.owner + "." + m.name + m.desc);
                }
            }
        }
    }

    // ===== ModularUI2 transformation =====

    /**
     * ModularUI2 screens do not render inside GuiContainer's foreground layer.
     * ClientScreenHandler draws them from the GuiScreen draw event instead, so
     * the flag has to be set around its real draw entry points.
     */
    private byte[] transformModularUiScreenHandler(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming ModularUI2 ClientScreenHandler...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                boolean isScreenInternal = method.name.equals("drawScreenInternal")
                        && method.desc.equals(
                                "(Lcom/cleanroommc/modularui/screen/ModularScreen;Lnet/minecraft/client/gui/GuiScreen;IIF)V");
                boolean isContainer = method.name.equals("drawContainer")
                        && method.desc.equals(
                                "(Lcom/cleanroommc/modularui/screen/ModularScreen;Lnet/minecraft/client/gui/inventory/GuiContainer;IIF)V");

                if (isScreenInternal || isContainer) {
                    patchMethodWithContainerFlag(method);
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] ModularUI2 ClientScreenHandler transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming ClientScreenHandler: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    // ===== ModularUI2 text renderer transformation =====

    /**
     * ModularUI2's TextRenderer is the single place where all its widgets turn
     * their theme colors into draw calls. Correcting the color here guarantees
     * coverage even if a screen draws outside ClientScreenHandler's flagged scope.
     */
    private byte[] transformModularUiTextRenderer(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming ModularUI2 TextRenderer...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                if (method.name.equals("setColor") && method.desc.equals("(I)V")) {
                    System.out.println("[DarkTextFix]   Patching: " + method.name + method.desc);

                    InsnList inject = new InsnList();
                    inject.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    inject.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "darktext/DarkTextHelper",
                            "fixColorForGui",
                            "(I)I",
                            false));
                    inject.add(new VarInsnNode(Opcodes.ISTORE, 1));
                    method.instructions.insert(inject);
                }

                // Belt and suspenders: correct the active color right before the
                // actual font draw, even if a caller bypassed setColor().
                if (method.name.equals("draw") && method.desc.equals("(Ljava/lang/String;FF)V")) {
                    System.out.println("[DarkTextFix]   Patching: " + method.name + method.desc);

                    InsnList fixField = new InsnList();
                    fixField.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    fixField.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    fixField.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            "com/cleanroommc/modularui/drawable/text/TextRenderer",
                            "color",
                            "I"));
                    fixField.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "darktext/DarkTextHelper",
                            "fixColorForGui",
                            "(I)I",
                            false));
                    fixField.add(new FieldInsnNode(
                            Opcodes.PUTFIELD,
                            "com/cleanroommc/modularui/drawable/text/TextRenderer",
                            "color",
                            "I"));
                    method.instructions.insert(fixField);
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] ModularUI2 TextRenderer transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming ModularUI2 TextRenderer: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    // ===== Angelica batched font transformation =====

    /**
     * Angelica routes most text through BatchingFontRenderer when font batching
     * is enabled, bypassing the patch on FontRenderer.drawString(). Patch the
     * batcher's entry point as well; fixColor() is idempotent for already-light colors.
     */
    private byte[] transformBatchingFontRenderer(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming Angelica BatchingFontRenderer...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                if (method.name.equals("drawString")
                        && method.desc.equals("(FFIZZLjava/lang/CharSequence;II)F")) {
                    patchColorParam(method, 3);
                    patchFormattingCodeColors(method, "colorCode");
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] BatchingFontRenderer transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming BatchingFontRenderer: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    // ===== Item overlay suppression =====

    /**
     * Item stack counts and durability/charge text are drawn by RenderItem.
     * Suppress correction there so ModularUI2's drawContainer() flag does not
     * recolor item overlays.
     */
    private byte[] transformRenderItem(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming RenderItem...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                if ((method.name.equals("renderItemOverlayIntoGUI") || method.name.equals("func_94148_a"))
                        && method.desc.equals(
                                "(Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/client/renderer/texture/TextureManager;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V")) {
                    patchFontCallsWithItemOverlay(method);
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] RenderItem transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming RenderItem: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    // ===== Shared flag helpers =====

    /**
     * Wraps only the FontRenderer draw calls inside RenderItem with the
     * inItemOverlay flag. Setting it for the whole method is unsafe: mixins can
     * add early returns that bypass the reset and leave the flag stuck on.
     */
    private void patchFontCallsWithItemOverlay(MethodNode method) {
        int patchCount = 0;
        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();

        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();
            if (!(node instanceof MethodInsnNode) || node.getOpcode() != Opcodes.INVOKEVIRTUAL)
                continue;

            MethodInsnNode call = (MethodInsnNode) node;
            if (!call.owner.equals("net/minecraft/client/gui/FontRenderer"))
                continue;
            if (!(call.name.equals("drawStringWithShadow") || call.name.equals("func_78261_a")
                    || call.name.equals("drawString") || call.name.equals("func_78276_b")
                    || call.name.equals("func_85187_a")))
                continue;

            InsnList setFlag = new InsnList();
            setFlag.add(new InsnNode(Opcodes.ICONST_1));
            setFlag.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", "inItemOverlay", "Z"));
            method.instructions.insertBefore(node, setFlag);

            InsnList resetFlag = new InsnList();
            resetFlag.add(new InsnNode(Opcodes.ICONST_0));
            resetFlag.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", "inItemOverlay", "Z"));
            method.instructions.insert(node, resetFlag);

            patchCount++;
        }

        System.out.println("[DarkTextFix]   Wrapped " + patchCount + " item overlay font call(s)");
    }

    /** Sets inContainerGui = true at method entry and false before every return. */
    private void patchMethodWithContainerFlag(MethodNode method) {
        System.out.println("[DarkTextFix]   Patching: " + method.name + method.desc);
        patchMethodWithFlag(method, "inContainerGui");
    }

    /** Sets a boolean DarkTextHelper flag to true at method entry and false before every return. */
    private void patchMethodWithFlag(MethodNode method, String fieldName) {
        InsnList enter = new InsnList();
        enter.add(new InsnNode(Opcodes.ICONST_1));
        enter.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", fieldName, "Z"));
        method.instructions.insert(enter);

        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();
            if (node.getOpcode() == Opcodes.RETURN) {
                // Insert before any labels/frames directly preceding the return so
                // jumps targeting those labels cannot skip the reset.
                AbstractInsnNode insertPoint = node;
                while (insertPoint.getPrevious() != null && insertPoint.getPrevious().getOpcode() == -1) {
                    insertPoint = insertPoint.getPrevious();
                }
                InsnList exit = new InsnList();
                exit.add(new InsnNode(Opcodes.ICONST_0));
                exit.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", fieldName, "Z"));
                method.instructions.insertBefore(insertPoint, exit);
            }
        }
    }
}
