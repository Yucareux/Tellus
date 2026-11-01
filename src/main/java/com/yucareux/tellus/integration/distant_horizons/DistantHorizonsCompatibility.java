package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.platform.TellusPlatform;

/**
 * What the installed Distant Horizons supports, recorded when the integration starts. This class never loads DH
 * classes, so client screens can use it whether or not DH is installed.
 */
public final class DistantHorizonsCompatibility {
   /** The release with the Tellus builds of DH; keep it on the same release as DH_RELEASE in the CI workflow. */
   public static final String TELLUS_BUILD_URL = "https://github.com/Yucareux/Tellus/releases/tag/distant-horizons-3.3.4-tellus.1";
   private static volatile boolean integrationEnabled;
   private static volatile boolean tellusBuild;

   private DistantHorizonsCompatibility() {
   }

   static void record(boolean apiSupported, boolean generationGateAvailable) {
      integrationEnabled = apiSupported;
      // Only the Tellus fork adds the generation gate; older fork builds predate API 7.1 and get no integration.
      tellusBuild = apiSupported && generationGateAvailable;
   }

   /** True when Tellus generates the LODs of the installed DH (API 7.1 or newer). */
   public static boolean isIntegrationEnabled() {
      return integrationEnabled;
   }

   /** True when DH is installed but is not the Tellus build, which alone keeps Increase Height terrain in its LODs. */
   public static boolean isIncompatibleBuildInstalled() {
      return TellusPlatform.isModLoaded("distanthorizons") && !tellusBuild;
   }
}
