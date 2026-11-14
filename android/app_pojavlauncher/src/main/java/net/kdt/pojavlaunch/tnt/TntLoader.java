package net.kdt.pojavlaunch.tnt;

/** Сборки, которые показываются в списке версий вперемешку с ванильными. */
public enum TntLoader {
    VANILLA("", "Vanilla", new String[0]),
    FABRIC("Fabric", "Fabric", new String[]{"fabric"}),
    FABRIC_SODIUM("Fabric+Sodium", "Fabric", new String[]{"fabric"}),
    QUILT("Quilt", "Quilt", new String[]{"quilt", "fabric"}),
    FORGE("Forge", "Forge", new String[]{"forge"}),
    FORGE_OPTIFINE("Forge+OptiFine", "Forge", new String[]{"forge"}),
    OPTIFINE("OptiFine", "OptiFine", new String[0]),
    LITELOADER("LiteLoader", "LiteLoader", new String[]{"liteloader"});

    /** Название в списке версий. */
    public final String title;
    /** Имя папки при изоляции. */
    public final String folder;
    /** Загрузчики Modrinth, моды для которых подходят. */
    public final String[] modLoaders;

    TntLoader(String title, String folder, String[] modLoaders) {
        this.title = title;
        this.folder = folder;
        this.modLoaders = modLoaders;
    }

    public boolean supportsMods() { return modLoaders.length > 0; }

    /** Папка сборки внутри instances/ (как на ПК: Fabric-1.20.1, Forge+OptiFine-1.20.1, 1.20.1). */
    public String instanceName(String mc) {
        return this == VANILLA ? mc : title + "-" + mc;
    }
}
