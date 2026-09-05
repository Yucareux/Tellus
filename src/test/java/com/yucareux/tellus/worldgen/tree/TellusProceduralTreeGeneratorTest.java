package com.yucareux.tellus.worldgen.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.world.data.resolve.ResolveBiome;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.ResolveRealm;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.Test;

class TellusProceduralTreeGeneratorTest {
   @Test
   void planningIsDeterministicAndDoesNotTakeHorizontalScale() {
      TellusCanopyHeightSource.CanopySample canopy = canopy(34.0, 38.0, 42.0);

      TellusProceduralTreeGenerator.TreePlan first = TellusProceduralTreeGenerator.plan(
         TellusProceduralTreeGenerator.Profile.TROPICAL, canopy, 912341L
      );
      TellusProceduralTreeGenerator.TreePlan second = TellusProceduralTreeGenerator.plan(
         TellusProceduralTreeGenerator.Profile.TROPICAL, canopy, 912341L
      );

      assertEquals(first, second);
      assertTrue(first.present());
      assertFalse(first.bush());
      assertTrue(first.dataDriven());
   }

   @Test
   void zeroCanopyProducesACompactBiomeBush() {
      TellusProceduralTreeGenerator.TreePlan plan = TellusProceduralTreeGenerator.plan(
         TellusProceduralTreeGenerator.Profile.TROPICAL, canopy(0.0, 0.0, 0.0), 17L
      );

      assertTrue(plan.present());
      assertTrue(plan.bush());
      assertTrue(plan.dataDriven());
      assertEquals(TellusProceduralTreeGenerator.Profile.TROPICAL, plan.profile());
      assertEquals(3, plan.height());
      assertEquals(1, plan.trunkRadius());
      assertEquals(2, plan.crownRadius());
   }

   @Test
   void tallCanopyCanProduceEmergentGiantTrees() {
      int tallest = 0;
      TellusCanopyHeightSource.CanopySample canopy = canopy(76.0, 84.0, 92.0);
      for (long seed = 0; seed < 512; seed++) {
         tallest = Math.max(
            tallest,
            TellusProceduralTreeGenerator.plan(
               TellusProceduralTreeGenerator.Profile.TROPICAL, canopy, seed
            ).height()
         );
      }

      assertTrue(tallest >= 88, "expected at least one near-maximum emergent tree, got " + tallest);
      assertTrue(tallest <= 96);
   }

   @Test
   void coastRedwoodCalibrationCorrectsTheObservedThirtyMeterCanopy() {
      TellusCanopyHeightSource.CanopySample observed = new TellusCanopyHeightSource.CanopySample(
         true,
         31.0,
         32.3,
         32.0,
         34.0,
         35.0,
         35.0,
         13,
         9
      );
      long redwoodHeightTotal = 0L;
      long genericHeightTotal = 0L;
      int tallest = 0;
      int maturePlans = 0;
      for (long seed = 0; seed < 2_048; seed++) {
         TellusProceduralTreeGenerator.TreePlan redwood = TellusProceduralTreeGenerator.plan(
            TellusProceduralTreeGenerator.Profile.COAST_REDWOOD, observed, seed
         );
         TellusProceduralTreeGenerator.TreePlan generic = TellusProceduralTreeGenerator.plan(
            TellusProceduralTreeGenerator.Profile.TALL_CONIFER, observed, seed
         );
         redwoodHeightTotal += redwood.height();
         genericHeightTotal += generic.height();
         tallest = Math.max(tallest, redwood.height());
         if (redwood.height() >= 48) {
            maturePlans++;
            assertTrue(redwood.crownBase() >= Math.floor(redwood.height() * 0.57));
         }
      }

      double redwoodAverage = redwoodHeightTotal / 2_048.0;
      double genericAverage = genericHeightTotal / 2_048.0;
      assertTrue(redwoodAverage >= 48.0, "expected corrected redwood average, got " + redwoodAverage);
      assertTrue(redwoodAverage >= genericAverage + 18.0);
      assertTrue(tallest >= 68 && tallest <= 112);
      assertTrue(maturePlans > 1_000);
      assertEquals(112.0, TellusProceduralTreeGenerator.calibratedCoastRedwoodHeight(100.0));
   }

   @Test
   void northernCaliforniaCoastalForestUsesARegionalSpeciesMix() {
      ResolveEcoregion redwoodRegion = new ResolveEcoregion(
         359,
         "Northern California coastal forests",
         ResolveBiome.TEMPERATE_CONIFER_FORESTS,
         "Temperate Conifer Forests",
         ResolveRealm.NEARCTIC,
         "Nearctic",
         "CC-BY 4.0"
      );
      int redwoods = 0;
      int tallConifers = 0;
      int broadleaves = 0;
      for (long seed = 0; seed < 1_000; seed++) {
         switch (TellusProceduralTreeGenerator.regionalProfile(
            TellusProceduralTreeGenerator.Profile.TALL_CONIFER, redwoodRegion, seed
         )) {
            case COAST_REDWOOD -> redwoods++;
            case TALL_CONIFER -> tallConifers++;
            case TEMPERATE_BROADLEAF -> broadleaves++;
            default -> {
            }
         }
      }

      assertTrue(redwoods >= 520 && redwoods <= 720, "unexpected redwood share " + redwoods);
      assertTrue(tallConifers >= 150 && tallConifers <= 330, "unexpected conifer share " + tallConifers);
      assertTrue(broadleaves >= 70 && broadleaves <= 220, "unexpected broadleaf share " + broadleaves);
      assertEquals(
         TellusProceduralTreeGenerator.Profile.TALL_CONIFER,
         TellusProceduralTreeGenerator.regionalProfile(
            TellusProceduralTreeGenerator.Profile.TALL_CONIFER, ResolveEcoregion.UNKNOWN, 42L
         )
      );
   }

   @Test
   void placementAnchorsAreStableAndStayInsideTheirNineBlockCells() {
      TellusProceduralTreeGenerator.TreeAnchor first =
         TellusProceduralTreeGenerator.anchorForCell(-3, 4, 9238475L);
      TellusProceduralTreeGenerator.TreeAnchor second =
         TellusProceduralTreeGenerator.anchorForCell(-3, 4, 9238475L);

      assertEquals(first, second);
      assertTrue(first.worldX() >= -27 && first.worldX() <= -19);
      assertTrue(first.worldZ() >= 36 && first.worldZ() <= 44);

      RandomSource legacyPlacementRandom = RandomSource.create(first.seed());
      assertEquals(-27 + legacyPlacementRandom.nextInt(9), first.worldX());
      assertEquals(36 + legacyPlacementRandom.nextInt(9), first.worldZ());
   }

   @Test
   void registryFreePreviewPlanningUsesTheFullDetailBiomeProfiles() {
      assumeFalse(isMinecraftForge(), "Forge's raw JUnit bootstrap cannot initialize vanilla biome registry keys");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      assertEquals(
         TellusProceduralTreeGenerator.Profile.TROPICAL,
         TellusProceduralTreeGenerator.plan(
            Biomes.JUNGLE, ResolveEcoregion.UNKNOWN, null, 11L
         ).profile()
      );
      assertEquals(
         TellusProceduralTreeGenerator.Profile.SAVANNA,
         TellusProceduralTreeGenerator.plan(
            Biomes.WINDSWEPT_SAVANNA, ResolveEcoregion.UNKNOWN, null, 12L
         ).profile()
      );
      assertEquals(
         TellusProceduralTreeGenerator.Profile.TALL_CONIFER,
         TellusProceduralTreeGenerator.plan(
            Biomes.OLD_GROWTH_SPRUCE_TAIGA, ResolveEcoregion.UNKNOWN, null, 13L
         ).profile()
      );
      assertEquals(
         TellusProceduralTreeGenerator.Profile.BIRCH,
         TellusProceduralTreeGenerator.plan(
            Biomes.OLD_GROWTH_BIRCH_FOREST, ResolveEcoregion.UNKNOWN, null, 14L
         ).profile()
      );
   }

   private static boolean isMinecraftForge() {
      try {
         Class.forName("net.minecraftforge.fml.ModList", false, TellusProceduralTreeGeneratorTest.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException ignored) {
         return false;
      }
   }

   @Test
   void tropicalPineOakEcoregionsDoNotUseABorealSpruceShape() {
      ResolveEcoregion pineOak = ecoregion(
         559,
         "Trans-Mexican Volcanic Belt pine-oak forests",
         ResolveBiome.TROPICAL_CONIFEROUS_FORESTS,
         ResolveRealm.NEOTROPIC
      );
      int pines = 0;
      int broadleaves = 0;
      for (long seed = 0; seed < 1_000; seed++) {
         TellusProceduralTreeGenerator.Profile profile = TellusProceduralTreeGenerator.regionalProfile(
            TellusProceduralTreeGenerator.Profile.CONIFER, pineOak, seed
         );
         if (profile == TellusProceduralTreeGenerator.Profile.PINE) {
            pines++;
         } else if (profile == TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF) {
            broadleaves++;
         }
      }

      assertTrue(pines >= 580 && pines <= 780, "unexpected pine share " + pines);
      assertTrue(broadleaves >= 220 && broadleaves <= 420, "unexpected oak share " + broadleaves);
   }

   @Test
   void resolveNamesAndRealmsSelectDistinctRegionalGrowthForms() {
      ResolveEcoregion mallee = ecoregion(
         198,
         "Esperance mallee",
         ResolveBiome.MEDITERRANEAN_FORESTS_WOODLANDS_SCRUB,
         ResolveRealm.AUSTRALASIA
      );
      ResolveEcoregion icelandBirch = ecoregion(
         711,
         "Iceland boreal birch forests and alpine tundra",
         ResolveBiome.BOREAL_FORESTS_TAIGA,
         ResolveRealm.PALEARCTIC
      );

      for (long seed = 0; seed < 128; seed++) {
         TellusProceduralTreeGenerator.Profile malleeProfile = TellusProceduralTreeGenerator.regionalProfile(
            TellusProceduralTreeGenerator.Profile.CONIFER, mallee, seed
         );
         assertTrue(
            malleeProfile == TellusProceduralTreeGenerator.Profile.MALLEE
               || malleeProfile == TellusProceduralTreeGenerator.Profile.EUCALYPTUS
         );
         TellusProceduralTreeGenerator.Profile birchProfile = TellusProceduralTreeGenerator.regionalProfile(
            TellusProceduralTreeGenerator.Profile.CONIFER, icelandBirch, seed
         );
         assertTrue(
            birchProfile == TellusProceduralTreeGenerator.Profile.SUBARCTIC_BIRCH
               || birchProfile == TellusProceduralTreeGenerator.Profile.CONIFER
         );
      }
   }

   @Test
   void observedCanopyHeightRemainsTheMainHeightSignal() {
      TellusCanopyHeightSource.CanopySample observed = canopy(34.0, 36.0, 42.0);
      long total = 0L;
      for (long seed = 0; seed < 2_048; seed++) {
         total += TellusProceduralTreeGenerator.plan(
            TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF, observed, seed
         ).height();
      }

      double average = total / 2_048.0;
      assertTrue(average >= 31.0 && average <= 38.0, "unexpected data-driven average " + average);
   }

   private static ResolveEcoregion ecoregion(
      int ecoId, String name, ResolveBiome biome, ResolveRealm realm
   ) {
      return new ResolveEcoregion(
         ecoId,
         name,
         biome,
         biome.displayName(),
         realm,
         realm.name(),
         "CC-BY 4.0"
      );
   }

   private static TellusCanopyHeightSource.CanopySample canopy(
      double center, double percentile75, double maximum
   ) {
      return new TellusCanopyHeightSource.CanopySample(
         true,
         center,
         percentile75,
         percentile75,
         percentile75,
         maximum,
         maximum,
         13,
         9
      );
   }
}
