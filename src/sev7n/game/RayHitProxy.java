package sev7n.game;

import sev7n.lang.runtime.SevenObject;
import sev7n.math.Vec3;

/**
 * What raycast() gives scripts when the ray hits something:
 *   hit.entity    what it hit (none for a tile)
 *   hit.point     where, in the world
 *   hit.normal    which way the surface faces there (length 1)
 *   hit.distance  how far along the ray
 */
final class RayHitProxy implements SevenObject {

    private final EntityProxy entity;
    private final Vec3 point;
    private final Vec3 normal;
    private final double distance;

    RayHitProxy(EntityProxy entity, Vec3 point, Vec3 normal, double distance) {
        this.entity = entity;
        this.point = point;
        this.normal = normal;
        this.distance = distance;
    }

    @Override
    public String typeName() {
        return "hit";
    }

    @Override
    public java.util.Collection<String> propertyNames() {
        return java.util.List.of("entity", "point", "normal", "distance");
    }

    @Override
    public Object getProperty(String name, int line) {
        return switch (name) {
            case "entity" -> entity;
            case "point" -> point.copy();
            case "normal" -> normal.copy();
            case "distance" -> distance;
            default -> throw noSuchProperty(name, line);
        };
    }

    @Override
    public String toString() {
        return "<hit " + (entity == null ? "tile" : entity.entity().name) + " at " + point + ">";
    }
}
