package sev7n.editor;

import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.internal.flag.ImGuiDockNodeFlags;
import imgui.type.ImInt;

/**
 * The default panel arrangement (Window > Reset Layout restores it):
 *
 *   Hierarchy | Scene / Game / Script / Eights | Inspector
 *             |-----------------------|
 *             | Console / Project     |
 */
final class DockLayout {

    private DockLayout() { }

    static void build(int dockspace) {
        float width = ImGui.getMainViewport().getWorkSizeX();
        float height = ImGui.getMainViewport().getWorkSizeY();
        imgui.internal.ImGui.dockBuilderRemoveNode(dockspace);
        imgui.internal.ImGui.dockBuilderAddNode(dockspace, ImGuiDockNodeFlags.DockSpace);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspace, width, height);

        ImInt left = new ImInt();
        ImInt centerAndRight = new ImInt();
        ImInt right = new ImInt();
        ImInt center = new ImInt();
        ImInt bottom = new ImInt();
        ImInt top = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspace, ImGuiDir.Left, 0.16f, left, centerAndRight);
        imgui.internal.ImGui.dockBuilderSplitNode(centerAndRight.get(), ImGuiDir.Right, 0.24f, right, center);
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Down, 0.27f, bottom, top);

        imgui.internal.ImGui.dockBuilderDockWindow(HierarchyPanel.TITLE, left.get());
        imgui.internal.ImGui.dockBuilderDockWindow(InspectorPanel.TITLE, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(SceneViewPanel.TITLE, top.get());
        imgui.internal.ImGui.dockBuilderDockWindow(GameViewPanel.TITLE, top.get());
        imgui.internal.ImGui.dockBuilderDockWindow(ScriptEditorPanel.TITLE, top.get());
        imgui.internal.ImGui.dockBuilderDockWindow(EightsPanel.TITLE, top.get());
        imgui.internal.ImGui.dockBuilderDockWindow(ConsolePanel.TITLE, bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(ProjectPanel.TITLE, bottom.get());
        imgui.internal.ImGui.dockBuilderFinish(dockspace);
    }
}
