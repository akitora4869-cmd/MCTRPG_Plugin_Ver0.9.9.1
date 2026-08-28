package Rin.TRPGCharacter;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Random;

/**
 * クトゥルフ神話風ドリームランド地形。
 *
 * 外部ワールド生成プラグインに依存せず、Paper/Bukkit標準APIだけで
 * 「黒い海・異様な山稜・深い裂け目・黒曜石の遺構・菌類の森」を生成する。
 */
public class DreamlandsGenerator extends ChunkGenerator {

    private static final int SEA_LEVEL = 48;
    private static final int MIN_SURFACE = 28;
    private static final int MAX_SURFACE = 122;

    @Override
    public void generateNoise(WorldInfo worldInfo,
                              Random random,
                              int chunkX,
                              int chunkZ,
                              ChunkData chunkData) {
        long seed = worldInfo.getSeed();

        SimplexOctaveGenerator continental = new SimplexOctaveGenerator(seed ^ 0x4D5954484F534C41L, 6);
        continental.setScale(0.0065);

        SimplexOctaveGenerator ridges = new SimplexOctaveGenerator(seed ^ 0x524C5945485F4E47L, 4);
        ridges.setScale(0.018);

        SimplexOctaveGenerator detail = new SimplexOctaveGenerator(seed ^ 0x445245414D5F4E31L, 3);
        detail.setScale(0.045);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkX * 16 + lx;
                int wz = chunkZ * 16 + lz;

                double c = continental.noise(wx, wz, 0.55, 0.5, true);
                double r = Math.abs(ridges.noise(wx, wz, 0.55, 0.5, true));
                double d = detail.noise(wx, wz, 0.5, 0.5, true);

                // 広い低地と、急に隆起する不自然な山地を混在させる。
                double heightValue = 58.0 + c * 31.0 + Math.pow(r, 2.1) * 36.0 + d * 7.0;

                // 原点周辺は初期スポーン確保のため穏やかな高台にする。
                double dist = Math.sqrt((double) wx * wx + (double) wz * wz);
                if (dist < 40.0) {
                    double blend = Math.max(0.0, 1.0 - dist / 40.0);
                    heightValue = heightValue * (1.0 - blend) + 72.0 * blend;
                }

                int surface = clamp((int) Math.round(heightValue), MIN_SURFACE, MAX_SURFACE);

                // 基盤。
                chunkData.setBlock(lx, worldInfo.getMinHeight(), lz, Material.BEDROCK);
                for (int y = worldInfo.getMinHeight() + 1; y <= surface; y++) {
                    Material material;
                    int depth = surface - y;

                    if (depth == 0) {
                        material = pickSurface(c, r, d, wx, wz);
                    } else if (depth <= 3) {
                        material = pickSubsurface(c, r, wx, wz);
                    } else if (y < 18) {
                        material = Material.DEEPSLATE;
                    } else {
                        material = ((hash(wx, y, wz, seed) & 15L) == 0L)
                                ? Material.TUFF
                                : Material.DEEPSLATE;
                    }
                    chunkData.setBlock(lx, y, lz, material);
                }

                // 黒い海。浅瀬はほぼ作らず、岸が急に落ち込むようにする。
                if (surface < SEA_LEVEL) {
                    for (int y = surface + 1; y <= SEA_LEVEL; y++) {
                        chunkData.setBlock(lx, y, lz, Material.WATER);
                    }
                }

                // 地表の散発的な異物。
                if (surface > SEA_LEVEL + 2) {
                    long h = hash(wx, 0, wz, seed);

                    // 黒い石柱。遠景でシルエットが見える程度の密度。
                    if ((h & 0x3FFL) == 17L && dist > 28.0) {
                        int height = 7 + (int) ((h >>> 12) & 15L);
                        for (int y = 1; y <= height && surface + y < worldInfo.getMaxHeight() - 1; y++) {
                            Material mat = (y % 5 == 0) ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN;
                            chunkData.setBlock(lx, surface + y, lz, mat);
                        }
                    }

                    // 菌類・スカルクのような異様な植生。
                    if ((h & 0x7FL) == 5L) {
                        chunkData.setBlock(lx, surface + 1, lz, Material.SCULK_VEIN);
                    } else if ((h & 0x1FFL) == 31L) {
                        chunkData.setBlock(lx, surface + 1, lz, Material.WARPED_FUNGUS);
                    } else if ((h & 0x3FFL) == 63L) {
                        chunkData.setBlock(lx, surface + 1, lz, Material.RED_MUSHROOM);
                    }
                }

                // 深い裂け目を点在させる。地表近くのみえぐり、地下全体を軽くしすぎない。
                double fissure = Math.abs(detail.noise(wx + 4312, wz - 9771, 0.5, 0.5, true));
                if (fissure < 0.035 && dist > 48.0 && surface > SEA_LEVEL + 8) {
                    int bottom = Math.max(worldInfo.getMinHeight() + 5, surface - 30);
                    for (int y = bottom; y <= surface + 2; y++) {
                        chunkData.setBlock(lx, y, lz, Material.AIR);
                    }
                }

                // Paper 1.20.1ではChunkData#setBiomeが利用できないため、
                // バイオームは既定値のまま使用する。幻想的な景観は地形・ブロックで表現する。
            }
        }
    }

    @Override
    public boolean shouldGenerateSurface() {
        // 地表層はこのジェネレーター自身で構成する。
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        // 岩盤もこのジェネレーター自身で敷設する。
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    private Material pickSurface(double continental, double ridge, double detail, int x, int z) {
        long h = hash(x, 0, z, 0xD34DCAFE1234L);
        if (continental < -0.38) return Material.SOUL_SAND;
        if (ridge > 0.62) return Material.BLACKSTONE;
        if (detail > 0.45) return Material.SCULK;
        if ((h & 7L) == 0L) return Material.COARSE_DIRT;
        if ((h & 15L) == 1L) return Material.MOSS_BLOCK;
        return Material.DEEPSLATE;
    }

    private Material pickSubsurface(double continental, double ridge, int x, int z) {
        long h = hash(x, 1, z, 0x55AA33CC77L);
        if (continental < -0.38) return Material.SOUL_SOIL;
        if (ridge > 0.62) return Material.BASALT;
        return (h & 3L) == 0L ? Material.TUFF : Material.DEEPSLATE;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private long hash(int x, int y, int z, long seed) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) z * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
