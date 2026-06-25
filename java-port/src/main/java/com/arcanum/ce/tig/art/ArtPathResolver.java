package com.arcanum.ce.tig.art;

/**
 * Resolves a {@code tig_art_id_t} to a repository file path. Mirrors the C
 * {@code TigArtFilePathResolver} callback (set via {@code TigInitInfo}).
 *
 * The TIG layer resolves only system/MISC art itself; for every other type it
 * delegates to a resolver supplied by the game layer (in the C engine that is
 * {@code name_resolve_path} in {@code name.c}). Register one with
 * {@link com.arcanum.ce.tig.TigArt#setFilePathResolver}.
 */
@FunctionalInterface
public interface ArtPathResolver {
    /** @return the resolved path (TIG backslash form is fine), or null on failure. */
    String resolve(int artId);
}
