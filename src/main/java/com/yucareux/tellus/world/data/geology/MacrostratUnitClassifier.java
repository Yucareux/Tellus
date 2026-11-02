package com.yucareux.tellus.world.data.geology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Macrostrat map unit's free text as bedrock.
 *
 * <p>Maps describe their units in different ways: a structured rock list ({@code Major:{shale,limestone}}), a plain
 * list ("quartzite, slate, sandstone and limestone"), or only a name and a description ("Red, slope-forming,
 * thin-bedded siltstone and sandstone"). The rock list is read first, then the name, then the description. In a list or
 * name the first rock is the main one and the next different one the second; in a description the rock named most is.
 * Rocks listed after "Minor" get a smaller share. The first colour word picks a colour variant. Loose cover (alluvium, till, landslides), ice and water give no
 * bedrock, so the caller falls back to the global rock map.</p>
 */
final class MacrostratUnitClassifier {
   // Gravel, sand and clay from the Pliocene on are loose cover; older ones are conglomerate, sandstone and mudstone.
   // Maps put the Pleistocene base at 2.58 or 2.588 Ma, so a Quaternary cut-off would split moraines.
   private static final double LOOSE_MAX_AGE_MA = 5.333;
   private static final int SECOND_PERCENT = 30;
   private static final int MINOR_PERCENT = 15;
   // Past the first sentences a description turns to its members, so later words seldom describe the unit.
   private static final int DESCRIPTION_ROCK_TOKENS = 120;
   private static final int DESCRIPTION_COLOUR_TOKENS = 40;
   // "mafic" in "mafic and ultramafic rocks" still qualifies "rocks" three words on.
   private static final int QUALIFIER_REACH = 3;
   private static final int MAFIC = 1;
   private static final int FELSIC = 2;
   private static final int INTERMEDIATE = 3;

   private static final Map<String, Rock> NOUNS = nouns();
   // Loose when young, rock when old.
   private static final Map<String, Rock> LOOSE = Map.of(
      "gravel", Rock.CONGLOMERATE,
      "boulder", Rock.CONGLOMERATE,
      "cobble", Rock.CONGLOMERATE,
      "pebble", Rock.CONGLOMERATE,
      "breccia", Rock.CONGLOMERATE,
      "sand", Rock.SANDSTONE,
      "silt", Rock.SHALE,
      "clay", Rock.SHALE,
      "mud", Rock.SHALE
   );
   private static final Set<String> COVER = Set.of(
      "alluvium", "alluvial", "colluvium", "colluvial", "landslide", "landslip", "talus", "scree", "till", "moraine",
      "morainal", "drift", "outwash", "loess", "dune", "eolian", "aeolian", "terrace", "unconsolidated", "surficial",
      "lacustrine", "playa", "beach", "swamp", "marsh", "peat", "fill", "debris", "rubble", "regolith", "residuum", "soil",
      "ice", "glacier", "snow", "firn", "water"
   );
   private static final Map<String, Rock> ADJECTIVES = Map.of(
      "granitic", Rock.GRANITE,
      "basaltic", Rock.BASALT,
      "carbonate", Rock.LIMESTONE,
      "siliciclastic", Rock.GREY_SANDSTONE,
      "clastic", Rock.GREY_SANDSTONE,
      "metasedimentary", Rock.SCHIST,
      "mylonitic", Rock.SCHIST,
      "metamorphic", Rock.SCHIST
   );
   private static final Set<String> VOLCANIC = Set.of("volcanic", "extrusive", "lava");
   private static final Set<String> PLUTONIC = Set.of("intrusive", "intrusion", "plutonic", "pluton", "igneous");
   private static final Set<String> GENERIC = Set.of("rock", "complex", "suite", "body");
   private static final Set<String> MAFIC_WORDS = Set.of("mafic", "basic");
   private static final Set<String> FELSIC_WORDS = Set.of("felsic", "acid", "acidic", "silicic");
   private static final Set<String> RED_WORDS = Set.of(
      "red", "reddish", "maroon", "orange", "orangish", "pink", "pinkish", "purple", "purplish", "brown", "brownish",
      "vermilion", "variegated"
   );
   private static final Set<String> WHITE_WORDS = Set.of("white", "whitish", "cream", "ivory");
   private static final Set<String> NEUTRAL_WORDS = Set.of(
      "gray", "grey", "grayish", "greyish", "green", "greenish", "olive", "tan", "buff", "yellow", "yellowish", "beige"
   );

   private MacrostratUnitClassifier() {
   }

   /**
    * @param bottomAgeMa age of the unit's base in millions of years, or NaN when the map gives none
    * @return the unit's bedrock, or null for cover, ice, water and text with no rock words
    */
   static MapUnit classify(String name, String lith, String descrip, double bottomAgeMa) {
      boolean young = Double.isFinite(bottomAgeMa) && bottomAgeMa <= LOOSE_MAX_AGE_MA;
      String[] texts = {lith, name, descrip};
      int[] tokenLimits = {Integer.MAX_VALUE, Integer.MAX_VALUE, DESCRIPTION_ROCK_TOKENS};
      List<Found> found = List.of();
      boolean described = false;
      // A vague "volcanic" or "metamorphic rocks" gives way to the next text that names a rock, such as "Ash-flow tuff".
      for (int i = 0; i < texts.length && (found.isEmpty() || found.get(0).vague()); i++) {
         List<Found> next = rocks(texts[i], young, tokenLimits[i]);
         if (!next.isEmpty() && (found.isEmpty() || !next.get(0).vague())) {
            found = next;
            described = i == texts.length - 1;
         }
      }
      if (found.isEmpty() || found.get(0).rock() == null) {
         return null;
      }

      Tint tint = tint(descrip, DESCRIPTION_COLOUR_TOKENS);
      if (tint == null) {
         tint = tint(name, Integer.MAX_VALUE);
      }
      if (tint == null) {
         tint = tint(lith, Integer.MAX_VALUE);
      }
      if (tint == null) {
         tint = Tint.NONE;
      }

      return described ? byMentions(found, tint) : inOrder(found, tint);
   }

   /** A rock list names its main rock first. */
   private static MapUnit inOrder(List<Found> found, Tint tint) {
      Rock main = tinted(found.get(0).rock(), tint);
      for (int i = 1; i < found.size(); i++) {
         Found next = found.get(i);
         if (next.rock() != null) {
            Rock second = tinted(next.rock(), tint);
            if (second != main) {
               return new MapUnit(main, second, next.minor() ? MINOR_PERCENT : SECOND_PERCENT);
            }
         }
      }
      return MapUnit.of(main);
   }

   /**
    * A description often opens with its top member, so the rock it names most is the main one: the Kaibab Formation's
    * opens on gypsum but names limestone more. The rock named first wins a tie.
    */
   private static MapUnit byMentions(List<Found> found, Tint tint) {
      Map<Rock, Double> weights = new LinkedHashMap<>();
      for (Found next : found) {
         if (next.rock() != null) {
            weights.merge(tinted(next.rock(), tint), next.minor() ? 0.5 : 1.0, Double::sum);
         }
      }
      weights.merge(tinted(found.get(0).rock(), tint), 0.5, Double::sum);
      Rock main = null;
      Rock second = null;
      for (Map.Entry<Rock, Double> entry : weights.entrySet()) {
         if (main == null || entry.getValue() > weights.get(main)) {
            second = main;
            main = entry.getKey();
         } else if (second == null || entry.getValue() > weights.get(second)) {
            second = entry.getKey();
         }
      }
      return second == null ? MapUnit.of(main) : new MapUnit(main, second, weights.get(second) >= 1.0 ? SECOND_PERCENT : MINOR_PERCENT);
   }

   /** Rocks in the order the text names them; a null rock marks cover, ice or water. */
   private static List<Found> rocks(String text, boolean young, int maxTokens) {
      List<String> tokens = tokens(text);
      List<Found> found = new ArrayList<>();
      int qualifier = 0;
      int qualifierAge = 0;
      Rock adjective = null;
      int adjectiveAge = 0;
      boolean minor = false;
      for (int i = 0; i < Math.min(tokens.size(), maxTokens); i++) {
         String token = base(tokens.get(i));
         qualifierAge++;
         adjectiveAge++;
         if (qualifierAge > QUALIFIER_REACH) {
            qualifier = 0;
         }
         if (adjectiveAge > QUALIFIER_REACH) {
            adjective = null;
         }

         Found rock = null;
         if (token.equals("minor")) {
            minor = true;
         } else if (MAFIC_WORDS.contains(token) || FELSIC_WORDS.contains(token) || token.equals("intermediate")) {
            qualifier = MAFIC_WORDS.contains(token) ? MAFIC : FELSIC_WORDS.contains(token) ? FELSIC : INTERMEDIATE;
            qualifierAge = 0;
         } else if (NOUNS.containsKey(token)) {
            rock = new Found(NOUNS.get(token), false, minor);
         } else if (LOOSE.containsKey(token)) {
            rock = new Found(young ? null : LOOSE.get(token), false, minor);
         } else if (COVER.contains(token) || token.contains("glaci")) {
            rock = new Found(null, false, minor);
         } else if (VOLCANIC.contains(token)) {
            rock = new Found(qualifier == MAFIC ? Rock.BASALT : qualifier == FELSIC ? Rock.RHYOLITE : Rock.ANDESITE, qualifier == 0, minor);
         } else if (PLUTONIC.contains(token) || (GENERIC.contains(token) && qualifier != 0)) {
            rock = new Found(plutonic(qualifier), qualifier == 0, minor);
         } else if (ADJECTIVES.containsKey(token)) {
            adjective = ADJECTIVES.get(token);
            adjectiveAge = 0;
         } else if (GENERIC.contains(token) && adjective != null) {
            rock = new Found(adjective, adjective == Rock.SCHIST, minor);
         }

         if (rock != null) {
            found.add(rock);
            // A qualifier or adjective describes one rock: "mafic volcanic rocks" is basalt, not basalt and gabbro.
            qualifier = 0;
            adjective = null;
         }
      }
      return found;
   }

   private static Rock plutonic(int qualifier) {
      return switch (qualifier) {
         case MAFIC -> Rock.GABBRO;
         case INTERMEDIATE -> Rock.DIORITE;
         default -> Rock.GRANITE;
      };
   }

   /** The first colour word, or null when there is none. */
   private static Tint tint(String text, int maxTokens) {
      List<String> tokens = tokens(text);
      for (int i = 0; i < Math.min(tokens.size(), maxTokens); i++) {
         String token = tokens.get(i);
         String next = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
         if (RED_WORDS.contains(token)) {
            return Tint.RED;
         } else if (WHITE_WORDS.contains(token)) {
            return Tint.WHITE;
         } else if (token.equals("black") || token.equals("blackish")) {
            return Tint.DARK;
         } else if (token.equals("dark")) {
            return RED_WORDS.contains(next) ? Tint.RED : Tint.DARK;
         } else if (token.equals("light") || token.equals("pale")) {
            return RED_WORDS.contains(next) ? Tint.RED : next.equals("colored") || next.equals("coloured") ? Tint.WHITE : Tint.NONE;
         } else if (NEUTRAL_WORDS.contains(token)) {
            return Tint.NONE;
         }
      }
      return null;
   }

   private static Rock tinted(Rock rock, Tint tint) {
      return switch (tint) {
         case RED -> switch (rock) {
            case SANDSTONE, QUARTZITE -> Rock.RED_SANDSTONE;
            case SHALE -> Rock.RED_MUDSTONE;
            case GRANITE -> Rock.PINK_GRANITE;
            default -> rock;
         };
         case WHITE -> switch (rock) {
            case LIMESTONE -> Rock.CHALK;
            case GRANITE -> Rock.GREY_GRANITE;
            default -> rock;
         };
         case DARK -> switch (rock) {
            case SANDSTONE -> Rock.GREY_SANDSTONE;
            case SHALE -> Rock.BLACK_SHALE;
            case LIMESTONE, DOLOMITE -> Rock.DARK_LIMESTONE;
            default -> rock;
         };
         case NONE -> rock;
      };
   }

   private static List<String> tokens(String text) {
      List<String> tokens = new ArrayList<>();
      if (text == null || text.isEmpty()) {
         return tokens;
      }
      String lower = text.toLowerCase(Locale.ROOT);
      int start = -1;
      for (int i = 0; i <= lower.length(); i++) {
         boolean letter = i < lower.length() && Character.isLetter(lower.charAt(i));
         if (letter && start < 0) {
            start = i;
         } else if (!letter && start >= 0) {
            tokens.add(lower.substring(start, i));
            start = -1;
         }
      }
      return tokens;
   }

   /** Singular form of a known word: "rocks" is "rock", "gneisses" is "gneiss". */
   private static String base(String token) {
      if (known(token)) {
         return token;
      } else if (token.endsWith("es") && known(token.substring(0, token.length() - 2))) {
         return token.substring(0, token.length() - 2);
      } else if (token.endsWith("s") && known(token.substring(0, token.length() - 1))) {
         return token.substring(0, token.length() - 1);
      }
      return token;
   }

   private static boolean known(String token) {
      return NOUNS.containsKey(token) || LOOSE.containsKey(token) || COVER.contains(token) || ADJECTIVES.containsKey(token)
         || VOLCANIC.contains(token) || PLUTONIC.contains(token) || GENERIC.contains(token);
   }

   private static Map<String, Rock> nouns() {
      Map<String, Rock> nouns = new HashMap<>();
      put(nouns, Rock.SANDSTONE, "sandstone", "arenite", "arkose", "subarkose", "litharenite", "quartzarenite", "grit", "gritstone", "molasse");
      put(nouns, Rock.GREY_SANDSTONE, "greywacke", "graywacke", "wacke", "flysch", "turbidite");
      put(nouns, Rock.CONGLOMERATE, "conglomerate", "diamictite", "tillite", "fanglomerate", "puddingstone");
      put(nouns, Rock.SHALE, "shale", "siltstone", "mudstone", "claystone", "argillite", "mudrock", "lutite");
      put(nouns, Rock.LIMESTONE,
         "limestone", "calcarenite", "calcilutite", "calcirudite", "micrite", "biomicrite", "oolite", "coquina", "travertine",
         "tufa", "reef", "wackestone", "packstone", "grainstone", "boundstone", "floatstone", "rudstone");
      put(nouns, Rock.DOLOMITE, "dolomite", "dolostone", "dolomicrite");
      put(nouns, Rock.CHALK, "chalk");
      put(nouns, Rock.MARL, "marl", "marlstone");
      put(nouns, Rock.EVAPORITE, "gypsum", "anhydrite", "halite", "salt", "evaporite", "potash");
      put(nouns, Rock.GRANITE,
         "granite", "granitoid", "pegmatite", "aplite", "leucogranite", "monzogranite", "syenogranite", "adamellite", "alaskite",
         "microgranite");
      put(nouns, Rock.DIORITE, "granodiorite", "tonalite", "trondhjemite", "diorite", "monzonite", "monzodiorite", "syenite", "anorthosite");
      put(nouns, Rock.GABBRO, "gabbro", "norite", "troctolite", "diabase", "dolerite", "gabbronorite", "lamprophyre", "microgabbro");
      put(nouns, Rock.ULTRAMAFIC,
         "peridotite", "dunite", "harzburgite", "lherzolite", "wehrlite", "pyroxenite", "hornblendite", "serpentinite", "serpentine",
         "ultramafic", "ultrabasic", "ophiolite", "komatiite", "kimberlite");
      put(nouns, Rock.RHYOLITE, "rhyolite", "dacite", "rhyodacite", "obsidian", "felsite", "porphyry", "keratophyre", "perlite");
      put(nouns, Rock.ANDESITE, "andesite", "trachyte", "trachyandesite", "latite", "phonolite");
      put(nouns, Rock.BASALT, "basalt", "basanite", "tholeiite", "picrite", "hawaiite", "mugearite", "spilite", "pillow", "trachybasalt", "tephrite");
      put(nouns, Rock.TUFF,
         "tuff", "ignimbrite", "tephra", "pumice", "scoria", "lapilli", "agglomerate", "pyroclastic", "volcaniclastic", "tuffite",
         "hyaloclastite", "ash");
      put(nouns, Rock.SLATE, "slate", "phyllite");
      put(nouns, Rock.SCHIST,
         "schist", "semischist", "pelite", "semipelite", "metapelite", "hornfels", "mylonite", "phyllonite", "greenschist", "blueschist",
         "metasediment", "metagreywacke", "metasiltstone", "calcsilicate", "skarn");
      put(nouns, Rock.GNEISS, "gneiss", "orthogneiss", "paragneiss", "migmatite", "granulite", "charnockite", "psammite", "semipsammite", "metagranite");
      put(nouns, Rock.QUARTZITE, "quartzite", "metaquartzite", "orthoquartzite", "metachert");
      put(nouns, Rock.MARBLE, "marble", "metacarbonate", "metalimestone", "cipolin");
      put(nouns, Rock.AMPHIBOLITE, "amphibolite", "metabasalt", "metagabbro", "metabasite", "greenstone", "eclogite", "metavolcanic", "metadolerite");
      return Map.copyOf(nouns);
   }

   private static void put(Map<String, Rock> nouns, Rock rock, String... words) {
      for (String word : words) {
         if (nouns.put(word, rock) != null) {
            throw new IllegalStateException("Rock word listed twice: " + word);
         }
      }
   }

   private enum Tint {
      NONE,
      RED,
      WHITE,
      DARK
   }

   /** @param vague read from a bare "volcanic" or "intrusive" with nothing to say which */
   private record Found(Rock rock, boolean vague, boolean minor) {
   }
}
