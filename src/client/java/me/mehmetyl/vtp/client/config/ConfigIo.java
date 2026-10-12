package me.mehmetyl.vtp.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.mehmetyl.vtp.VtpConstants;
import me.mehmetyl.vtp.VtpMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves {@link VtpConfig} as {@code config/villager_trade_scanner.json}.
 *
 * <p>Neither method throws. A broken config costs the player their settings; it
 * must never cost them the game. Failures are logged once each rather than on
 * every reload.
 */
public final class ConfigIo {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(VtpConstants.MOD_ID + ".json");

    private static boolean loadFailureLogged;
    private static boolean saveFailureLogged;

    private ConfigIo() {
    }

    public static VtpConfig load() {
        if (!Files.exists(FILE)) {
            VtpConfig fresh = new VtpConfig();
            save(fresh);
            return fresh;
        }

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            VtpConfig config = GSON.fromJson(reader, VtpConfig.class);

            // fromJson returns null for an empty or all-whitespace file.
            if (config == null) {
                VtpMod.LOGGER.warn("{} was empty, using defaults", FILE.getFileName());
                return new VtpConfig();
            }

            if (config.schemaVersion > VtpConfig.CURRENT_SCHEMA) {
                // Written by a newer build. Gson has already ignored the keys we
                // do not know and our initialisers covered the ones it lacked, so
                // the object is usable -- but saving would drop the newer keys, so
                // keep a copy first.
                backup();
                VtpMod.LOGGER.warn(
                        "Config schema v{} is newer than this build's v{}; loaded what is understood "
                                + "and saved a copy to {}.bak",
                        config.schemaVersion, VtpConfig.CURRENT_SCHEMA, FILE.getFileName());
            }

            config.normalize();
            return config;
        } catch (Exception e) {
            // Catches IOException plus Gson's unchecked JsonSyntaxException /
            // JsonIOException -- a malformed file is the common case here.
            if (!loadFailureLogged) {
                loadFailureLogged = true;
                VtpMod.LOGGER.error("Could not read {}, falling back to defaults", FILE, e);
            }
            return new VtpConfig();
        }
    }

    public static void save(VtpConfig config) {
        try {
            Files.createDirectories(FILE.getParent());

            // Write to a sibling temp file and move it into place, so a crash or
            // a full disk mid-write leaves the previous config intact instead of
            // a truncated one.
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }

            try {
                Files.move(temp, FILE,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            if (!saveFailureLogged) {
                saveFailureLogged = true;
                VtpMod.LOGGER.error("Could not write {}", FILE, e);
            }
        }
    }

    private static void backup() {
        try {
            Files.copy(FILE, FILE.resolveSibling(FILE.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            VtpMod.LOGGER.warn("Could not back up {}", FILE, e);
        }
    }
}
