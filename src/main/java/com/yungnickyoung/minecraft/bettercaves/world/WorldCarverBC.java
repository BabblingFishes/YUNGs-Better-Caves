package com.yungnickyoung.minecraft.bettercaves.world;


import com.mojang.datafixers.Dynamic;
import com.yungnickyoung.minecraft.bettercaves.config.BetterCavesConfig;
import com.yungnickyoung.minecraft.bettercaves.config.Settings;
import com.yungnickyoung.minecraft.bettercaves.enums.CaveType;
import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
import com.yungnickyoung.minecraft.bettercaves.noise.FastNoise;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCaveUtil;
import com.yungnickyoung.minecraft.bettercaves.world.cave.AbstractBC;
import com.yungnickyoung.minecraft.bettercaves.world.cave.CaveBC;
import com.yungnickyoung.minecraft.bettercaves.world.cave.CavernBC;
import javafx.util.Pair;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.carver.*;
import net.minecraft.world.gen.feature.ProbabilityConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

public class WorldCarverBC extends WorldCarver<ProbabilityConfig> {
    // The minecraft world
    private IWorld world;
    private long seed; // world seed

    // Cave types
    private AbstractBC caveCubic;
    private AbstractBC caveSimplex;

    // Cavern types
    private AbstractBC cavernLava;
    private AbstractBC cavernFloored;
    private AbstractBC cavernWater;

    private int surfaceCutoff;

    // Vanilla cave gen if user sets config to use it
    public final WorldCarver<ProbabilityConfig> vanillaCave = new CaveWorldCarver(ProbabilityConfig::deserialize, 256);
    public final WorldCarver<ProbabilityConfig> vanillaCaveUnderwater = new UnderwaterCaveWorldCarver(ProbabilityConfig::deserialize);

    // Cellular noise (basically Voronoi diagrams) generators to group caves into cave "biomes" based on xz-coordinates
    private FastNoise waterCavernController;
    private FastNoise cavernBiomeController;
    private FastNoise caveBiomeController;

    // Biome generation noise thresholds, based on user config
    private float cubicCaveThreshold;
    private float simplexCaveThreshold;
    private float lavaCavernThreshold;
    private float flooredCavernThreshold;
    private float waterBiomeThreshold;

    // Config option for using vanilla cave gen in some areas
    private boolean enableVanillaCaves;

    // Config option for using water biomes
    private boolean enableWaterBiomes;

    public static final Logger LOGGER = LogManager.getLogger(Settings.MOD_ID + " WorldCarverBC");
    private Set<Pair<Integer, Integer>> coordList = new HashSet<>();

    public WorldCarverBC(Function<Dynamic<?>, ? extends ProbabilityConfig> p_i49929_1_, int p_i49929_2_) {
        super(p_i49929_1_, p_i49929_2_);
    }

    @Override
    public boolean carve(IChunk chunkIn, Random rand, int seaLevel, int chunkX, int chunkZ, int centerChunkX, int centerChunkZ, BitSet carvingMask, ProbabilityConfig config) {
        Pair<Integer, Integer> pair = new Pair<>(chunkX, chunkZ);
        if (coordList.contains(pair))
            return false;

        coordList.add(pair);

        LOGGER.info("(" + chunkX + ", " + chunkZ + ")  ---- (" + centerChunkX + ", " + centerChunkX + ")");

        // Find the (approximate) lowest and highest surface altitudes in this chunk
//        int maxSurfaceHeight = BetterCaveUtil.getMaxSurfaceHeight(primer);
//        int minSurfaceHeight = BetterCaveUtil.getMinSurfaceHeight(primer);

        int maxSurfaceHeight = 128;
        int minSurfaceHeight = 60;

        // Debug visualizer options
        if (BetterCavesConfig.enableDebugVisualizer) {
            maxSurfaceHeight = 128;
        }

        // Cave generators - we will determine exactly what type these are based on the cave biome for each column
        AbstractBC cavernGen;
        AbstractBC caveGen;

        // These values probably could be hardcoded, but are kept as vars for future extensibility.
        // These values are later set to the correct cave type's config vars for
        // caveBottom and caveTop (only applicable for caverns, since caves perform some additional
        // operations to smoothly transition into the surface)
        int cavernBottomY;
        int cavernTopY;
        int caveBottomY;

//        if (world.getDimension().getType() == DimensionType.OVERWORLD) { // Only use Better Caves generation in overworld
            // We split chunks into 2x2 subchunks for surface height calculations
            for (int subX = 0; subX < 8; subX++) {
                for (int subZ = 0; subZ < 8; subZ++) {
                    if (!BetterCavesConfig.enableDebugVisualizer)
                        maxSurfaceHeight = BetterCaveUtil.getMaxSurfaceHeightSubChunk(chunkIn, subX, subZ);

                    for (int offsetX = 0; offsetX < 2; offsetX++) {
                        for (int offsetZ = 0; offsetZ < 2; offsetZ++) {
                            int localX = (subX * 2) + offsetX; // chunk-local x-coordinate (0-15, inclusive)
                            int localZ = (subZ * 2) + offsetZ; // chunk-local z-coordinate (0-15, inclusive)
                            int realX = (chunkX * 16) + localX;
                            int realZ = (chunkZ * 16) + localZ;

                            /* --------------------------- Configure Caves --------------------------- */

                            // Get noise values used to determine cave biome
//                            float caveBiomeNoise = caveBiomeController.GetNoise(realX, realZ);

                            /* Determine cave type for this column. We have two thresholds, one for cubic caves and one for
                             * simplex caves. Since the noise value generated for the biome is between -1 and 1, we (by
                             * default) designate all negative values as cubic caves, and all positive as simplex. However,
                             * we allow the user to tweak the cutoff values based on the frequency they designate for each cave
                             * type, so we must also check for values between the two thresholds,
                             * e.g. if (cubicCaveThreshold <= noiseValue < simplexCaveThreshold).
                             * In this case, we use vanilla cave generation if it is enabled; otherwise we dig no caves
                             * out of this chunk.
                             */
//                            if (caveBiomeNoise < this.cubicCaveThreshold) {
////                                caveGen = this.caveCubic;
////                                caveBottomY = BetterCavesConfig.cubicCaveBottom;
////                            } else if (caveBiomeNoise >= this.simplexCaveThreshold) {
////                                caveGen = this.caveSimplex;
////                                caveBottomY = BetterCavesConfig.simplexCaveBottom;
////                            } else {
////                                if (this.enableVanillaCaves) {
////                                    vanillaCave.carve(chunkIn, rand, seaLevel, chunkX, chunkZ, centerChunkX, centerChunkZ, carvingMask, config);
////                                    return true;
////                                }
////                                continue;
////                            }

                            caveGen = this.caveSimplex;
                            caveBottomY = 1;

                            /* --------------------------- Configure Caverns --------------------------- */

//                            // Get noise values used to determine cavern biome
//                            float cavernBiomeNoise = cavernBiomeController.GetNoise(realX, realZ);
//                            float waterBiomeNoise = 99;
//
//                            // Only bother calculating noise for water biome if enabled
//                            if (enableWaterBiomes)
//                                waterBiomeNoise = waterCavernController.GetNoise(realX, realZ);
//
//                            // If water biome threshold check is passed, change lava block to water
//                            BlockState lavaBlock = Blocks.LAVA.getDefaultState();
//                            if (waterBiomeNoise < waterBiomeThreshold)
//                                lavaBlock = Blocks.WATER.getDefaultState();
//
//                            // Determine cavern type for this column. Caverns generate at low altitudes only.
//                            if (cavernBiomeNoise < lavaCavernThreshold) {
//                                if (this.enableWaterBiomes && waterBiomeNoise < this.waterBiomeThreshold) {
//                                    // Generate water cavern in this column
//                                    cavernGen = this.cavernWater;
//                                } else {
//                                    // Generate lava cavern in this column
//                                    cavernGen = this.cavernLava;
//                                }
//                                // Water caverns use the same cave top/bottom as lava caverns
//                                cavernBottomY = BetterCavesConfig.lavaCavernCaveBottom;
//                                cavernTopY = BetterCavesConfig.lavaCavernCaveTop;
//                            } else if (cavernBiomeNoise >= lavaCavernThreshold && cavernBiomeNoise <= flooredCavernThreshold) {
//                                /* Similar to determining cave type above, we must check for values between the two adjusted
//                                 * thresholds, i.e. lavaCavernThreshold < noiseValue <= flooredCavernThreshold.
//                                 * In this case, we just continue generating the caves we were generating above, instead
//                                 * of generating a cavern.
//                                 */
//                                cavernGen = caveGen;
//                                cavernBottomY = caveBottomY;
//                                cavernTopY = caveBottomY;
//                            } else {
//                                // Generate floored cavern in this column
//                                cavernGen = this.cavernFloored;
//                                cavernBottomY = BetterCavesConfig.flooredCavernCaveBottom;
//                                cavernTopY = BetterCavesConfig.flooredCavernCaveTop;
//                            }

                            cavernGen = caveGen;
                            cavernBottomY = caveBottomY;
                            cavernTopY = caveBottomY;
                            BlockState lavaBlock = Blocks.LAVA.getDefaultState();

                            /* --------------- Dig out caves and caverns for this column --------------- */
                            // Top (Cave) layer:
                            caveGen.generateColumn(chunkX, chunkZ, chunkIn, localX, localZ, caveBottomY, maxSurfaceHeight,
                                    maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, lavaBlock);
                            // Bottom (Cavern) layer:
                            cavernGen.generateColumn(chunkX, chunkZ, chunkIn, localX, localZ, cavernBottomY, cavernTopY,
                                    maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, lavaBlock);

                        }
                    }
                }
            }
//        } else // use vanilla generation in other dimensions
//            vanillaCave.carve(chunkIn, rand, seaLevel, chunkX, chunkZ, centerChunkX, centerChunkZ, carvingMask, config);

        return true;
    }

    /**
     * @return threshold value for cubic cave spawn rate based on Config setting
     */
    private float calcCubicCaveThreshold() {
        switch (BetterCavesConfig.cubicCaveFreq) {
            case "None":
                return -99f;
            case "Rare":
                return -.6f;
            case "Common":
                return -.2f;
            default: // VeryCommon
                return 0;
        }
    }

    /**
     * @return threshold value for simplex cave spawn rate based on Config setting
     */
    private float calcSimplexCaveThreshold() {
        switch (BetterCavesConfig.simplexCaveFreq) {
            case "None":
                return 99f;
            case "Rare":
                return .6f;
            case "Common":
                return .2f;
            default: // VeryCommon
                return 0;
        }
    }

    /**
     * @return threshold value for lava cavern spawn rate based on Config setting
     */
    private float calcLavaCavernThreshold() {
        switch (BetterCavesConfig.lavaCavernCaveFreq) {
            case "None":
                return -99f;
            case "Rare":
                return -.8f;
            case "Common":
                return -.3f;
            case "VeryCommon":
                return -.1f;
            default: // Normal
                return -.4f;
        }
    }

    /**
     * @return threshold value for floored cavern spawn rate based on Config setting
     */
    private float calcFlooredCavernThreshold() {
        switch (BetterCavesConfig.flooredCavernCaveFreq) {
            case "None":
                return 99f;
            case "Rare":
                return .8f;
            case "Common":
                return .3f;
            case "VeryCommon":
                return .1f;
            default: // Normal
                return .4f;
        }
    }

    /**
     * @return threshold value for water biome spawn rate based on Config setting
     */
    private float calcWaterBiomeThreshold() {
        switch (BetterCavesConfig.waterBiomeFreq) {
            case "Rare":
                return -.4f;
            case "Common":
                return .1f;
            case "VeryCommon":
                return .3f;
            case "Always":
                return 99f;
            default: // Normal
                return -.15f;
        }
    }

    /**
     * Initialize Better Caves generators and cave biome controllers for this world.
     */
    public void initialize(IWorld worldIn) {
        this.world = worldIn;
        this.enableVanillaCaves = BetterCavesConfig.enableVanillaCaves;
        this.enableWaterBiomes = BetterCavesConfig.enableWaterBiomes;

        // Determine noise thresholds for cavern spawns based on user config
        this.lavaCavernThreshold = calcLavaCavernThreshold();
        this.flooredCavernThreshold = calcFlooredCavernThreshold();
        this.waterBiomeThreshold = calcWaterBiomeThreshold();

        // Determine noise thresholds for caverns based on user config
        this.cubicCaveThreshold = calcCubicCaveThreshold();
        this.simplexCaveThreshold = calcSimplexCaveThreshold();

        // Get user setting for surface cutoff depth used to close caves off towards the surface
        this.surfaceCutoff = BetterCavesConfig.surfaceCutoff;

        // Determine cave biome size
        float caveBiomeSize;
        switch (BetterCavesConfig.caveBiomeSize) {
            case "Small":
                caveBiomeSize = .007f;
                break;
            case "Large":
                caveBiomeSize = .0032f;
                break;
            case "ExtraLarge":
                caveBiomeSize = .001f;
                break;
            default: // Medium
                caveBiomeSize = .005f;
                break;
        }

        // Determine cavern biome size, as well as jitter to make Voronoi regions more varied in shape
        float cavernBiomeSize;
        float waterCavernBiomeSize = .0015f;
        switch (BetterCavesConfig.cavernBiomeSize) {
            case "Small":
                cavernBiomeSize = .01f;
                break;
            case "Large":
                cavernBiomeSize = .005f;
                break;
            case "ExtraLarge":
                cavernBiomeSize = .001f;
                waterCavernBiomeSize = .0005f;
                break;
            default: // Medium
                cavernBiomeSize = .007f;
                break;
        }

        // Initialize Biome Controllers using world seed and user config option for biome size
        this.caveBiomeController = new FastNoise();
        this.caveBiomeController.SetSeed((int)world.getSeed() + 222);
        this.caveBiomeController.SetFrequency(caveBiomeSize);
        this.caveBiomeController.SetCellularDistanceFunction(FastNoise.CellularDistanceFunction.Natural);

        this.cavernBiomeController = new FastNoise();
        this.cavernBiomeController.SetSeed((int)world.getSeed() + 333);
        this.cavernBiomeController.SetFrequency(cavernBiomeSize);
        this.cavernBiomeController.SetCellularDistanceFunction(FastNoise.CellularDistanceFunction.Natural);

        this.waterCavernController = new FastNoise();
        this.waterCavernController.SetSeed((int)world.getSeed() + 444);
        this.waterCavernController.SetFrequency(waterCavernBiomeSize);
        this.waterCavernController.SetCellularDistanceFunction(FastNoise.CellularDistanceFunction.Natural);

        /* ---------- Initialize all Better Cave generators using config options ---------- */
        this.caveCubic = new CaveBC(
                world,
                CaveType.CUBIC,
                BetterCavesConfig.cubicFractalOctaves,
                BetterCavesConfig.cubicFractalGain,
                BetterCavesConfig.cubicFractalFreq,
                BetterCavesConfig.cubicNumGenerators,
                BetterCavesConfig.cubicNoiseThreshold,
                BetterCavesConfig.cubicTurbulenceOctaves,
                BetterCavesConfig.cubicTurbulenceGain,
                BetterCavesConfig.cubicTurbulenceFreq,
                BetterCavesConfig.cubicEnableTurbulence,
                BetterCavesConfig.cubicYComp,
                BetterCavesConfig.cubicXZComp,
                BetterCavesConfig.cubicYAdjust,
                BetterCavesConfig.cubicYAdjustF1,
                BetterCavesConfig.cubicYAdjustF2,
                Blocks.QUARTZ_BLOCK.getDefaultState()
        );

        this.caveSimplex = new CaveBC(
                world,
                CaveType.SIMPLEX,
                BetterCavesConfig.simplexFractalOctaves,
                BetterCavesConfig.simplexFractalGain,
                BetterCavesConfig.simplexFractalFreq,
                BetterCavesConfig.simplexNumGenerators,
                BetterCavesConfig.simplexNoiseThreshold,
                BetterCavesConfig.simplexTurbulenceOctaves,
                BetterCavesConfig.simplexTurbulenceGain,
                BetterCavesConfig.simplexTurbulenceFreq,
                BetterCavesConfig.simplexEnableTurbulence,
                BetterCavesConfig.simplexYComp,
                BetterCavesConfig.simplexXZComp,
                BetterCavesConfig.simplexYAdjust,
                BetterCavesConfig.simplexYAdjustF1,
                BetterCavesConfig.simplexYAdjustF2,
                Blocks.COBBLESTONE.getDefaultState()
        );

        this.cavernLava = new CavernBC(
                world,
                CavernType.LAVA,
                BetterCavesConfig.lavaCavernFractalOctaves,
                BetterCavesConfig.lavaCavernFractalGain,
                BetterCavesConfig.lavaCavernFractalFreq,
                BetterCavesConfig.lavaCavernNumGenerators,
                BetterCavesConfig.lavaCavernNoiseThreshold,
                BetterCavesConfig.lavaCavernYComp,
                BetterCavesConfig.lavaCavernXZComp,
                Blocks.REDSTONE_BLOCK.getDefaultState()
        );

        this.cavernFloored = new CavernBC(
                world,
                CavernType.FLOORED,
                BetterCavesConfig.flooredCavernFractalOctaves,
                BetterCavesConfig.flooredCavernFractalGain,
                BetterCavesConfig.flooredCavernFractalFreq,
                BetterCavesConfig.flooredCavernNumGenerators,
                BetterCavesConfig.flooredCavernNoiseThreshold,
                BetterCavesConfig.flooredCavernYComp,
                BetterCavesConfig.flooredCavernXZComp,
                Blocks.GOLD_BLOCK.getDefaultState()
        );

        this.cavernWater = new CavernBC(
                world,
                CavernType.WATER,
                BetterCavesConfig.waterCavernFractalOctaves,
                BetterCavesConfig.waterCavernFractalGain,
                BetterCavesConfig.waterCavernFractalFreq,
                BetterCavesConfig.waterCavernNumGenerators,
                BetterCavesConfig.waterCavernNoiseThreshold,
                BetterCavesConfig.waterCavernYComp,
                BetterCavesConfig.waterCavernXZComp,
                Blocks.LAPIS_BLOCK.getDefaultState()
        );
    }

    @Override
    public boolean shouldCarve(Random rand, int chunkX, int chunkZ, ProbabilityConfig config) {
        return true;
    }

    @Override
    protected boolean func_222708_a(double p_222708_1_, double p_222708_3_, double p_222708_5_, int p_222708_7_) {
        return false;
    }
}