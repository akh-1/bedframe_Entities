package lol.sylvie.bedframe.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Minecraft translation keys to human-readable display strings for the
 * Bedrock pack's {@code .lang} files, in every language the loaded mods ship.
 *
 * <p><b>Why this exists:</b> Bedframe used to write a single {@code en_US.lang}
 * with {@code Text.translatable(key).getString()} directly. On a dedicated server,
 * {@link Text#translatable} resolves against {@code Language.getInstance()}, which
 * only holds translations that actually reached the server's language table —
 * vanilla, plus whatever Server Translations API loaded. Mods whose lang never
 * reaches that table (client-only mods like Terrestria/Cinderscapes loaded as
 * nested client jars, or Polymer-patched mods shipping assets but not the server
 * language) fell through to the fallback, which returns the <em>key itself</em>.
 * That's why Bedrock players saw {@code block.terrestria.rubber_button} instead of
 * "Rubber Button". And no non-English languages were emitted at all.
 *
 * <p><b>Per-key, per-language resolution.</b> For a target language L, each key is
 * resolved independently:
 * <ol>
 *   <li>(en_US only) {@code Text.translatable(key).getString()} — authoritative
 *       server-side translation when it resolves to something other than the key.</li>
 *   <li>The owning mod's {@code assets/<namespace>/lang/<L>.json} read straight off
 *       the classpath / nested jars via {@link ResourceHelper}.</li>
 *   <li>The owning mod's {@code en_us.json} — the English fallback, so a Spanish pack
 *       carries Spanish for mods that have it and English for mods that don't, exactly
 *       like vanilla Java per-key fallback.</li>
 *   <li>Humanize the key (last path segment, {@code snake_case} → Title Case) so the
 *       pack NEVER ships a raw key as a display name.</li>
 * </ol>
 */
public final class TranslationResolver {
    private TranslationResolver() {}

    /** The English source-of-truth file every mod is expected to have. */
    public static final String EN_US = "en_us";

    /**
     * Candidate languages, as (Java lang file code → Bedrock pack code). We only
     * probe/emit codes Bedrock actually understands; a {@code .lang} for a code the
     * Bedrock client doesn't recognise would just be dead weight. en_us is always
     * first. The Java code is the lowercase file name a mod ships
     * ({@code assets/<ns>/lang/es_es.json}); the Bedrock code is the
     * {@code texts/<code>.lang} name and the {@code languages.json} entry.
     */
    private static final LinkedHashMap<String, String> CANDIDATE_LANGS = new LinkedHashMap<>();
    static {
        // Always-present base.
        CANDIDATE_LANGS.put("en_us", "en_US");
        // Other languages supported by the Bedrock client.
        for (String java : new String[]{
            "en_gb","de_de","es_es","es_mx","fr_ca","fr_fr","it_it","ja_jp","ko_kr",
            "nl_nl","pt_br","pt_pt","ru_ru","zh_cn","zh_tw","bg_bg","cs_cz","da_dk",
            "el_gr","fi_fi","hu_hu","id_id","nb_no","pl_pl","sk_sk","sv_se","tr_tr","uk_ua"
        }) {
            CANDIDATE_LANGS.put(java, javaToBedrockCode(java));
        }
    }

    /**
     * Per-language resolution chain: which lang files to try, in order, before
     * falling back to English. The key insight (the es_MX bug) is that a regional
     * variant must fall back to its sibling variant of the SAME language before
     * English — a player on es_MX should get Spain-Spanish ({@code es_es}) for a mod
     * that only shipped {@code es_es.json}, NOT raw English. Variants of one language
     * fall back to each other mutually so a pack carries any available Spanish before
     * any English. Every chain ends at {@code en_us}; languages not listed here use
     * the default chain {@code [self, en_us]} (added lazily in {@link #langChain}).
     */
    private static final Map<String, java.util.List<String>> FALLBACK_CHAINS = new java.util.HashMap<>();
    static {
        FALLBACK_CHAINS.put("es_mx", java.util.List.of("es_mx", "es_es", "en_us"));
        FALLBACK_CHAINS.put("es_es", java.util.List.of("es_es", "es_mx", "en_us"));
        FALLBACK_CHAINS.put("pt_pt", java.util.List.of("pt_pt", "pt_br", "en_us"));
        FALLBACK_CHAINS.put("pt_br", java.util.List.of("pt_br", "pt_pt", "en_us"));
        FALLBACK_CHAINS.put("fr_ca", java.util.List.of("fr_ca", "fr_fr", "en_us"));
        FALLBACK_CHAINS.put("fr_fr", java.util.List.of("fr_fr", "fr_ca", "en_us"));
        FALLBACK_CHAINS.put("zh_tw", java.util.List.of("zh_tw", "zh_cn", "en_us"));
        FALLBACK_CHAINS.put("zh_cn", java.util.List.of("zh_cn", "zh_tw", "en_us"));
        FALLBACK_CHAINS.put("en_gb", java.util.List.of("en_gb", "en_us"));
        FALLBACK_CHAINS.put("en_us", java.util.List.of("en_us"));
    }

    /**
     * Returns the ordered fallback chain for a language: its explicit chain if one is
     * defined, otherwise {@code [self, en_us]}. Always ends at en_us.
     */
    public static java.util.List<String> langChain(String javaCode) {
        java.util.List<String> chain = FALLBACK_CHAINS.get(javaCode);
        if (chain != null) return chain;
        return EN_US.equals(javaCode) ? java.util.List.of(EN_US)
                                      : java.util.List.of(javaCode, EN_US);
    }

    /** (namespace + "/" + langCode) → (translation key → value). */
    private static final ConcurrentHashMap<String, Map<String, String>> LANG_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> EMPTY = Map.of();

    /** {@code es_es} → {@code es_ES}. */
    private static String javaToBedrockCode(String java) {
        int us = java.indexOf('_');
        if (us < 0) return java;
        return java.substring(0, us).toLowerCase() + "_" + java.substring(us + 1).toUpperCase();
    }

    /**
     * Loads (and caches) a mod's {@code lang/<langCode>.json} as a flat key→value
     * map. Returns an empty map (cached) if the file is missing or unreadable.
     */
    public static Map<String, String> getModLang(String namespace, String langCode) {
        return LANG_CACHE.computeIfAbsent(namespace + "/" + langCode, ignored -> {
            try (InputStream stream = ResourceHelper.getResource(namespace, "lang/" + langCode + ".json")) {
                if (stream == null) return EMPTY;
                JsonObject root = BedframeConstants.GSON.fromJson(
                    new InputStreamReader(stream), JsonObject.class);
                if (root == null) return EMPTY;
                Map<String, String> out = new LinkedHashMap<>(root.size());
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    JsonElement v = e.getValue();
                    if (v != null && v.isJsonPrimitive()) out.put(e.getKey(), v.getAsString());
                }
                return out.isEmpty() ? EMPTY : out;
            } catch (Exception e) {
                BedframeConstants.LOGGER.debug("Couldn't read {} lang for {}: {}", langCode, namespace, e.getMessage());
                return EMPTY;
            }
        });
    }

    /**
     * Determines which Bedrock languages to emit. A candidate is included when at
     * least one contributing mod ships a lang file for ANY non-English code in that
     * language's fallback chain — so es_MX is emitted (and populated from es_es) even
     * if no mod ships es_mx.json, which is exactly what a Mexican-Spanish client needs
     * to avoid falling through to the English base pack. en_US is always included.
     */
    public static LinkedHashMap<String, String> discoverLanguages(Iterable<String> namespaces) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put(EN_US, CANDIDATE_LANGS.get(EN_US)); // always
        for (Map.Entry<String, String> cand : CANDIDATE_LANGS.entrySet()) {
            String java = cand.getKey();
            if (java.equals(EN_US)) continue;
            boolean available = false;
            outer:
            for (String lang : langChain(java)) {
                if (lang.equals(EN_US)) continue; // en_us alone doesn't justify a localized file
                for (String ns : namespaces) {
                    if (!getModLang(ns, lang).isEmpty()) { available = true; break outer; }
                }
            }
            if (available) result.put(java, cand.getValue());
        }
        return result;
    }

    /**
     * Resolves a key for the English pack: server table → mod en_us.json → humanize.
     * Never returns the raw key.
     */
    public static String resolveEnglish(String javaKey) {
        if (javaKey == null || javaKey.isEmpty()) return javaKey;
        String viaServer = Text.translatable(javaKey).getString();
        if (!viaServer.equals(javaKey)) return viaServer;

        String namespace = namespaceOf(javaKey);
        if (namespace != null) {
            String fromMod = getModLang(namespace, EN_US).get(javaKey);
            if (fromMod != null && !fromMod.isEmpty()) return fromMod;
        }
        return humanize(javaKey);
    }

    /**
     * Resolves a key for a non-English pack by walking the language's fallback chain
     * (e.g. es_mx → es_es → en_us), returning the first hit. This is what makes an
     * es_MX pack carry Spain-Spanish for mods that only shipped es_es, instead of
     * dropping straight to raw English. Ends with humanize so it never ships a raw key.
     */
    public static String resolveLocalized(String javaKey, String langCode) {
        if (javaKey == null || javaKey.isEmpty()) return javaKey;
        String namespace = namespaceOf(javaKey);
        if (namespace != null) {
            for (String lang : langChain(langCode)) {
                String hit = getModLang(namespace, lang).get(javaKey);
                if (hit != null && !hit.isEmpty()) return hit;
            }
        }
        return humanize(javaKey);
    }

    /** Dispatches to {@link #resolveEnglish} or {@link #resolveLocalized}. */
    public static String resolve(String javaKey, String langCode) {
        return EN_US.equals(langCode) ? resolveEnglish(javaKey) : resolveLocalized(javaKey, langCode);
    }

    /**
     * Extracts the namespace from a standard translation key of the form
     * {@code <type>.<namespace>.<path...>} (block.terrestria.rubber_button →
     * "terrestria"). Returns null if there's no namespace segment.
     */
    public static String namespaceOf(String javaKey) {
        int first = javaKey.indexOf('.');
        if (first < 0) return null;
        int second = javaKey.indexOf('.', first + 1);
        return second < 0 ? javaKey.substring(first + 1) : javaKey.substring(first + 1, second);
    }

    /**
     * Title-cases a key's path portion as a last-resort display name.
     * {@code block.terrestria.rubber_button} → "Rubber Button".
     */
    public static String humanize(String javaKey) {
        int first = javaKey.indexOf('.');
        int second = first < 0 ? -1 : javaKey.indexOf('.', first + 1);
        String path = second >= 0 ? javaKey.substring(second + 1) : javaKey;
        String spaced = path.replace('_', ' ').replace('.', ' ').trim();
        if (spaced.isEmpty()) return javaKey;

        StringBuilder sb = new StringBuilder(spaced.length());
        boolean capitalizeNext = true;
        for (char c : spaced.toCharArray()) {
            if (c == ' ') { capitalizeNext = true; sb.append(' '); }
            else if (capitalizeNext) { sb.append(Character.toUpperCase(c)); capitalizeNext = false; }
            else sb.append(c);
        }
        return sb.toString();
    }
}
