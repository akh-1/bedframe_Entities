package lol.sylvie.bedframe.util;

import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ResourceHelper {
    public static ResourcePackBuilder PACK_BUILDER = null;
    public static ZipFile VANILLA_PACK = null;

    /**
     * Race-free snapshot of the Polymer pack builder's JSON/mcmeta resources.
     *
     * <p><b>Why this exists:</b> Polymer's {@code DefaultRPBuilder} stores every
     * generated file in a plain, non-thread-safe {@code HashMap} ({@code fileMap}).
     * {@code getData(path)} reads that map directly. But {@code buildResourcePack()}
     * runs ASYNC on a ForkJoinPool thread and MUTATES the same map while building —
     * most dangerously in the {@code sort_files} step, which inserts thousands of
     * null-valued directory-marker entries and forces the HashMap to resize.
     *
     * <p>Geyser's custom-block/item/entity events fire on Geyser's own timeline. If
     * one lands in the window between "pack build started" and "pack build finished",
     * Bedframe's {@link #getResourceUncached} would call {@code PACK_BUILDER.getData}
     * CONCURRENTLY with that resize — a classic HashMap data race. The read can then
     * return {@code null} spuriously, hand back another key's bytes mid-rehash, or
     * see a half-linked bucket. For a custom block (e.g. Farmers Delight's cooking
     * pot) that means its model/blockstate JSON intermittently "vanishes", the
     * geometry never gets written, and Bedrock shows the missing-geometry "?" cube.
     *
     * <p>The fix: at {@code RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT} — a point
     * where the fileMap is fully populated but the async build has NOT started yet —
     * we copy every {@code .json}/{@code .mcmeta} resource into this concurrent map.
     * From then on, those resources are served from here and NEVER from the live,
     * racy builder map. Textures (large, and benign if briefly wrong) keep using the
     * live builder + jar fallbacks. The snapshot is bounded (JSON only, a few MB) and
     * is rebuilt from scratch on every generation via the clear-at-start below.
     */
    private static final ConcurrentHashMap<String, byte[]> PACK_SNAPSHOT = new ConcurrentHashMap<>();

    /**
     * In-memory byte cache for resources that were successfully read once.
     *
     * <p>Why this exists: in dev environments (IntelliJ runs), the JVM and its
     * classloaders/jar FileSystems can survive across server restarts. After a few
     * restarts the zip FileSystem inside each ModContainer ends up in a degraded
     * "tepid" state where {@code Files.newInputStream} fails for some entries
     * even though they were perfectly readable on the first run. We saw this
     * manifest as an ever-growing list of "Resource not found, skipping: ..."
     * warnings — 7 on the first restart, 39 on the third, etc. — until the
     * config/bedframe/ directory was wiped and the dev env reset.
     *
     * <p>Caching the bytes the first time we successfully read a resource lets
     * us short-circuit the flaky FileSystem lookup on subsequent passes. The
     * cache is static so it survives within a single JVM process, which is
     * exactly the lifetime where the FileSystem degradation happens.
     */
    private static final ConcurrentHashMap<String, byte[]> BYTE_CACHE = new ConcurrentHashMap<>();

    /**
     * Maximum size (in bytes) for an individual entry kept in the in-memory cache.
     * Beyond this we still read the resource but skip caching it — preventing the
     * cache from ballooning with large texture PNGs on RAM-constrained servers
     * (4 GB or less). JSON files, blockstate files, and small icon textures fit
     * comfortably under this limit; large 256×256+ block textures do not.
     */
    private static final int BYTE_CACHE_MAX_ENTRY_BYTES = 16 * 1024; // 16 KB

    /**
     * Total cap on cached bytes. Once we approach this, no more entries are added —
     * the existing cache keeps serving hits, but new misses fall through to disk.
     * 4 MB total is enough to cache hundreds of small JSON/blockstate files without
     * hurting heap usage on a 4 GB server.
     */
    private static final int BYTE_CACHE_MAX_TOTAL_BYTES = 4 * 1024 * 1024; // 4 MB

    private static final java.util.concurrent.atomic.AtomicLong BYTE_CACHE_BYTES =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * Clears all in-memory caches. Call this after pack generation is complete
     * to release retained bytes for textures, JSON, and nested-jar mappings.
     * Subsequent {@link #getResource} calls will re-read from source.
     */
    /**
     * Set of resource paths that have been confirmed to NOT exist anywhere. The
     * Filament furniture fallback in BlockTranslator probes ~7 candidate model
     * paths per display-entity block; for hundreds of TSA furniture blocks,
     * that's thousands of expensive lookups that each touch PACK_BUILDER, the
     * thread context classloader, every loaded mod's findPath, and ultimately
     * scan every JAR file (including extracting nested JARs). Without negative
     * caching, the same "this doesn't exist" answer was being computed thousands
     * of times — easily 5+ minutes of redundant I/O.
     *
     * <p>The cache only grows during pack generation and is cleared at the end
     * via {@link #clearCaches()}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> MISS_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static void clearCaches() {
        BYTE_CACHE.clear();
        BYTE_CACHE_BYTES.set(0);
        MISS_CACHE.clear();
        // Note: NESTED_JAR_CACHE keeps its temp file references — those are tiny
        // (just Path objects) and the temp files self-delete on JVM exit. Clearing
        // them here would force re-extraction if pack generation runs again.
        //
        // Note: PACK_SNAPSHOT is intentionally NOT cleared here. clearCaches() runs
        // mid-generation (at the end of BlockTranslator.handle, before the item and
        // entity translators run), and those later translators still need to read
        // JSON from the snapshot. The snapshot is instead refreshed at the start of
        // each generation inside snapshotPackBuilder().
    }

    /**
     * Captures a race-free snapshot of the pack builder's JSON/mcmeta resources and
     * stores the builder reference for texture lookups.
     *
     * <p>MUST be called from {@code RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT}: at
     * that moment every mod's assets have been copied into the builder, but the async
     * {@code buildResourcePack()} has not started, so the underlying HashMap is stable
     * and safe to iterate. We only capture {@code .json}/{@code .mcmeta} — these are
     * the resources whose corruption produces the missing-geometry "?" cube, they are
     * small, and capturing them costs a few MB at most. Textures are deliberately left
     * out (they stream from the live builder + jar fallbacks; a briefly-wrong texture
     * is recoverable and never causes a hard geometry failure).
     *
     * <p>This also captures builder-only resources created via {@code addData()} — for
     * example generated canvas sign models — which exist ONLY in the builder map and
     * have no jar/classloader fallback, so the snapshot is the only race-free way to
     * read them later.
     */
    public static void snapshotPackBuilder(ResourcePackBuilder builder) {
        PACK_BUILDER = builder;
        if (builder == null) return;

        PACK_SNAPSHOT.clear();
        int[] count = {0};
        try {
            builder.forEachResource((path, resource) -> {
                if (path == null || resource == null) return;
                if (!path.endsWith(".json") && !path.endsWith(".mcmeta")) return;
                try {
                    byte[] bytes = resource.readAllBytes();
                    if (bytes != null) {
                        PACK_SNAPSHOT.put(path, bytes);
                        count[0]++;
                    }
                } catch (Throwable t) {
                    // Skip this one entry; a single unreadable resource must not abort
                    // the whole snapshot (which would re-expose the race for everything).
                }
            });
        } catch (Throwable t) {
            // If iteration itself fails, we still fall through safely: getResourceUncached
            // resolves JSON from the classloader/jar fallbacks instead of the racy builder.
            BedframeConstants.LOGGER.warn("Couldn't snapshot pack builder JSON ({}): {}",
                t.getClass().getSimpleName(), t.getMessage());
        }
        BedframeConstants.LOGGER.info("Bedframe: snapshotted {} JSON/mcmeta resources from pack builder", count[0]);
    }

    public static InputStream getResource(String path) {
        // Return cached bytes if we've successfully read this resource before.
        // See BYTE_CACHE docs above for why this matters in dev environments.
        byte[] cached = BYTE_CACHE.get(path);
        if (cached != null) return new ByteArrayInputStream(cached);

        // Short-circuit if we already know this path doesn't exist. Massive speedup
        // for code paths that probe many speculative paths (like the Filament
        // furniture fallback).
        if (MISS_CACHE.containsKey(path)) return null;

        InputStream rawStream = getResourceUncached(path);
        if (rawStream == null) {
            MISS_CACHE.put(path, Boolean.TRUE);
            return null;
        }
        // Read fully into memory so we can cache and return a fresh stream.
        try (InputStream in = rawStream) {
            byte[] bytes = in.readAllBytes();
            // Only cache small entries, and stop caching entirely once we've used
            // our overall budget. Large textures are copied straight to the pack
            // directory on disk anyway — keeping them in memory just wastes heap.
            if (bytes.length <= BYTE_CACHE_MAX_ENTRY_BYTES
                    && BYTE_CACHE_BYTES.get() + bytes.length <= BYTE_CACHE_MAX_TOTAL_BYTES) {
                if (BYTE_CACHE.putIfAbsent(path, bytes) == null) {
                    BYTE_CACHE_BYTES.addAndGet(bytes.length);
                }
            }
            return new ByteArrayInputStream(bytes);
        } catch (IOException e) {
            BedframeConstants.LOGGER.warn("Couldn't fully read resource {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static InputStream getResourceUncached(String path) {
        boolean isJsonLike = path.endsWith(".json") || path.endsWith(".mcmeta");

        // For JSON/mcmeta: read from the race-free snapshot ONLY. Never touch the live
        // PACK_BUILDER.getData() here — that HashMap is being mutated on a background
        // thread during pack build, and a concurrent read can return null/garbage,
        // which is exactly what made Farmers Delight's cooking pot intermittently lose
        // its geometry and render as the "?" cube on Bedrock. If the snapshot doesn't
        // have it (e.g. event fired before the snapshot, or a jar-only asset), we fall
        // through to the stable classloader/jar fallbacks below.
        if (isJsonLike) {
            byte[] snap = PACK_SNAPSHOT.get(path);
            if (snap != null) return new ByteArrayInputStream(snap);
        } else if (PACK_BUILDER != null) {
            // Non-JSON (textures, etc.): the live builder is still the best source, and
            // a transiently-bad texture is benign (worst case a one-frame wrong texture,
            // recoverable from the jar fallback) — it never produces a "?" geometry.
            // Guard it anyway so a concurrent-modification hiccup can't throw.
            try {
                byte[] data = PACK_BUILDER.getData(path);
                if (data != null) return new ByteArrayInputStream(data);
            } catch (Throwable ignored) {
                // Fall through to the classloader/jar fallbacks.
            }
        }

        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (stream != null) return stream;

        // Fallback: ask FabricLoader for the resource directly from each mod's own
        // paths. This covers two real-world cases that the classloader misses:
        //   1) Multi-module mods (terrestria-client, cinderscapes-client) whose assets
        //      live in a submodule jar that isn't always on the runtime classloader
        //      depending on how the mod is packaged.
        //   2) polymer-patch-bundle blocks (SmallLogPolymerBlock and friends) that
        //      register their texture assets in PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT
        //      — which fires AFTER Bedframe has already processed the blocks.
        //
        // First try findPath() on each accessible mod. This works for normal mods
        // but DOESN'T cover client-only submodules (terrestria-client, cinderscapes-client)
        // because Fabric Loader exposes them in getAllMods() but their assets are
        // gated behind {@code environment: client} on a dedicated server.
        try {
            for (net.fabricmc.loader.api.ModContainer mod
                    : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                Path resPath = mod.findPath(path).orElse(null);
                if (resPath == null) continue;
                try {
                    return Files.newInputStream(resPath);
                } catch (java.nio.file.NoSuchFileException ignored) {
                } catch (IOException ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        // SECOND fallback: scan the JAR files of all mods on the filesystem directly,
        // including nested JARs in META-INF/jars/.
        //
        // Client-only submodules (terrestria-client, cinderscapes-client) DON'T expose
        // their assets via findPath() on a dedicated server because Fabric considers
        // them unloaded. They're typically packaged as NESTED JARs inside the main mod
        // jar (e.g. terrestria-7.6.0.jar contains META-INF/jars/terrestria-client-7.6.0.jar).
        // We open both the outer jar and any nested jars it contains, looking for the
        // requested entry. This bypasses Fabric's environment-based access gates
        // completely — we're just doing raw file I/O.
        //
        // SKIP this expensive scan for assets/minecraft/ paths: minecraft's vanilla
        // assets only ever live in vanilla.zip (handled by the next fallback below),
        // never in mod JARs. Probing every loaded mod's nested JARs for "minecraft:"
        // resources just to come back empty was making the Filament furniture
        // fallback take 8+ minutes when it probed ~7 speculative paths per block.
        if (!path.startsWith("assets/minecraft/")) {
            try {
            for (net.fabricmc.loader.api.ModContainer mod
                    : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                // Skip mods whose origin is NESTED — their {@code getOrigin().getPaths()}
                // throws UnsupportedOperationException. These represent submodules that
                // live inside another mod's JAR (e.g. terrestria-client inside terrestria.jar);
                // their content gets scanned anyway when we hit the parent mod's outer JAR
                // and recurse into its META-INF/jars/.
                java.util.List<Path> origins;
                try {
                    origins = mod.getOrigin().getPaths();
                } catch (UnsupportedOperationException nestedSkip) {
                    continue;
                }
                for (Path origin : origins) {
                    if (!Files.isRegularFile(origin)) continue;
                    InputStream found = readFromJarRecursive(origin, path);
                    if (found != null) return found;
                }
            }
        } catch (Exception ignored) {
            // Silent — caller logs the "Resource not found" warn if all fallbacks fail.
        }
        } // end of: if (!path.startsWith("assets/minecraft/"))

        // VANILLA_PACK fallback. Don't NPE when the entry isn't there — return null
        // and let the caller decide what to do (typically: log warn, skip the resource).
        try {
            ZipEntry entry = VANILLA_PACK.getEntry(path);
            if (entry == null) return null;
            return VANILLA_PACK.getInputStream(entry);
        } catch (IOException e) {
            BedframeConstants.LOGGER.warn("Couldn't read resource {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Returns true if a custom mod resource exists in either the Polymer pack
     * builder or the mod classloader.
     *
     * <p>Polymer patch mods (e.g. an Enderscape polymer patch) register their
     * target mod's assets via {@link eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils#addModAssets}
     * but those assets are not materialised into {@code PACK_BUILDER} until the
     * pack is generated — which happens after Bedframe's item registration pass.
     * The classloader fallback finds them immediately from the mod JAR.
     *
     * <p><b>The vanilla pack is intentionally excluded.</b> Falling back to it
     * would cause vanilla item models to be misidentified as custom resources,
     * which would incorrectly process PolymerItems that return a vanilla model
     * identifier from {@code getPolymerItemModel()}.
     */
    public static boolean hasResource(String namespace, String path) {
        String fullPath = getResourcePath(namespace, path);

        boolean isJsonLike = fullPath.endsWith(".json") || fullPath.endsWith(".mcmeta");

        // JSON/mcmeta: consult the race-free snapshot, never the live builder map.
        // (hasResource is overwhelmingly called with blockstates/*.json and models/*.json,
        // so this is the common path.)
        if (isJsonLike) {
            if (PACK_SNAPSHOT.containsKey(fullPath)) return true;
        } else if (PACK_BUILDER != null) {
            try {
                if (PACK_BUILDER.getData(fullPath) != null) return true;
            } catch (Throwable ignored) {
                // Fall through to the classloader check.
            }
        }

        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fullPath);
        if (stream != null) {
            try { stream.close(); } catch (IOException ignored) {}
            return true;
        }

        return false;
    }

    public static String getResourcePath(String namespace, String path) {
        return "assets/" + namespace + "/" + path;
    }

    public static InputStream getResource(String namespace, String path) {
        return getResource(getResourcePath(namespace, path));
    }

    /**
     * Copies a resource from the mod's classpath / Polymer pack to the bedrock pack.
     * Returns true on success, false if the resource doesn't exist or copying failed.
     *
     * <p>Why the change from "throw on failure" to "return false": some modded blocks
     * reference textures that no longer exist in the version of the mod installed
     * (often happens with multi-module mods where the client-side textures live in a
     * jar that wasn't shipped, or with mods where the texture path moved between
     * versions). Throwing made one missing texture kill the entire block — the whole
     * {@code event.register(data)} chain bailed and the block never reached Geyser.
     * With a soft failure, the block still registers with whatever textures we
     * could resolve, and we just log a WARN for the missing one. The block may end
     * up using a fallback texture, but it won't be completely missing.
     */
    /**
     * Cache mapping (outer-jar-path, nested-entry-name) → extracted temp jar path.
     *
     * <p>Without this cache, every texture lookup that hits a nested JAR re-extracts
     * the entire nested JAR to a fresh temp file. For mods like Terrestria with 154
     * textures across the same nested {@code META-INF/jars/terrestria-client-7.6.0.jar},
     * that meant 154 full extractions on startup — each takes a noticeable chunk of
     * I/O time and disk space. With the cache, the nested JAR is extracted once and
     * reused for all subsequent reads. Startup dropped from ~8 minutes to ~30 seconds
     * in tests on a server with several multi-module mods loaded.
     *
     * <p>The temp files use {@code deleteOnExit()} so they get cleaned up when the
     * JVM exits. Map is concurrent for thread safety since multiple threads may
     * trigger resource reads in parallel.
     */
    private static final ConcurrentHashMap<String, Path> NESTED_JAR_CACHE = new ConcurrentHashMap<>();

    private static InputStream readFromJarRecursive(Path jarPath, String entryPath) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            // 1) Try direct entry in this jar.
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry != null) {
                try (InputStream zipIn = zip.getInputStream(entry)) {
                    return new ByteArrayInputStream(zipIn.readAllBytes());
                }
            }
            // 2) No direct hit — recurse into any nested jars under META-INF/jars/.
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.startsWith("META-INF/jars/") || !name.endsWith(".jar")) continue;

                // Cache key: outer jar absolute path + nested entry name.
                // Two different outer JARs may both contain a nested JAR with the same
                // entry name (rare but possible), so we include both in the key.
                String cacheKey = jarPath.toAbsolutePath().toString() + "!" + name;
                Path tempJar = NESTED_JAR_CACHE.get(cacheKey);
                if (tempJar == null || !Files.exists(tempJar)) {
                    // Extract once and cache. Use computeIfAbsent equivalent with
                    // re-check inside to avoid race conditions where two threads
                    // both think they need to extract.
                    synchronized (NESTED_JAR_CACHE) {
                        tempJar = NESTED_JAR_CACHE.get(cacheKey);
                        if (tempJar == null || !Files.exists(tempJar)) {
                            tempJar = java.nio.file.Files.createTempFile("bedframe-nested-", ".jar");
                            tempJar.toFile().deleteOnExit();
                            try (InputStream nestedIn = zip.getInputStream(e)) {
                                java.nio.file.Files.copy(nestedIn, tempJar,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                            NESTED_JAR_CACHE.put(cacheKey, tempJar);
                        }
                    }
                }
                InputStream nested = readFromJarRecursive(tempJar, entryPath);
                if (nested != null) return nested;
            }
        } catch (IOException ignored) {
            // Not a valid zip or can't read; caller will try the next.
        }
        return null;
    }

    public static boolean copyResource(String namespace, String path, Path destination) {
        try {
            // Always copy from source - don't skip if destination exists. In dev envs
            // a destination file from a previous server run can be stale or partially
            // written, and skipping the re-copy means the bad file is reused. Re-copying
            // every time is cheap (cached bytes from BYTE_CACHE) and guarantees the
            // pack contents match the current run.
            InputStream src = getResource(namespace, path);
            if (src == null) {
                BedframeConstants.LOGGER.warn("Resource not found, skipping: {}",
                    Identifier.of(namespace, path));
                return false;
            }
            destination.toFile().getParentFile().mkdirs(); // Filament, i'm lazy :P
            Files.copy(src, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | NullPointerException e) {
            BedframeConstants.LOGGER.warn("Couldn't copy resource {} (skipping): {}",
                Identifier.of(namespace, path), e.getMessage());
            return false;
        }
    }

    public static JsonObject readJsonResource(String namespace, String path) {
        try (InputStream stream = getResource(namespace, path)) {
            if (stream == null) {
                throw new RuntimeException("Resource not found: " + Identifier.of(namespace, path));
            }
            return BedframeConstants.GSON.fromJson(new InputStreamReader(stream), JsonObject.class);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't load resource " + Identifier.of(namespace, path), e);
        }
    }

    public static String javaToBedrockTexture(String javaPath) {
        return javaPath.replaceFirst("block", "blocks").replaceFirst("item", "items");
    }
}
