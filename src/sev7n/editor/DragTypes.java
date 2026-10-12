package sev7n.editor;

/** Names for things that can be dragged between panels. */
final class DragTypes {

    private DragTypes() { }

    /** An entity's id (Long), dragged within the Hierarchy to reorder. */
    static final String ENTITY = "sev7n.entity";

    /** A behaviour name (String), dragged from the Project panel onto an entity. */
    static final String SCRIPT = "sev7n.script";

    /** An imported picture or model (project-relative path String), dragged into the scene or onto an object. */
    static final String ASSET = "sev7n.asset";
}
