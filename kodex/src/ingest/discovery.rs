use std::path::{Path, PathBuf};
use anyhow::{Context, Result};
use walkdir::WalkDir;
use rayon::prelude::*;

pub struct DiscoveryResult {
    pub files: Vec<PathBuf>,
    pub semanticdb_dirs: Vec<PathBuf>,
}

/// Discover .semanticdb files from Mill's out/ directory.
///
/// Walks `<root>/out/` looking for `semanticDbDataDetailed.dest/data/META-INF/semanticdb/`
/// directories, then collects all `.semanticdb` files within them.
pub fn discover_semanticdb(root: &str) -> Result<DiscoveryResult> {
    let root = Path::new(root).canonicalize()
        .context("Failed to resolve workspace root")?;
    let out_dir = root.join("out");
    if !out_dir.exists() {
        anyhow::bail!("No out/ directory found at {}", root.display());
    }

    // Phase 1: Find all semanticDbDataDetailed.dest directories
    let sdb_dirs: Vec<PathBuf> = WalkDir::new(&out_dir)
        .max_depth(8)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_dir())
        .filter(|e| e.file_name() == "semanticDbDataDetailed.dest")
        .map(|e| e.into_path().join("data/META-INF/semanticdb"))
        .filter(|p| p.exists())
        .collect();

    // Phase 2: Walk each semanticdb directory for .semanticdb files (parallel)
    let files: Vec<PathBuf> = sdb_dirs.par_iter()
        .flat_map(|dir| {
            WalkDir::new(dir)
                .into_iter()
                .filter_map(|e| e.ok())
                .filter(|e| e.file_type().is_file())
                .filter(|e| e.path().extension().is_some_and(|ext| ext == "semanticdb"))
                .map(|e| e.into_path())
                .collect::<Vec<_>>()
        })
        .collect();

    // Fallback: check out/META-INF/semanticdb/ directly (scalac with -semanticdb-target)
    let fallback_dir = out_dir.join("META-INF/semanticdb");
    let mut all_files = files;
    if fallback_dir.exists() {
        let fallback_files: Vec<PathBuf> = WalkDir::new(&fallback_dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
            .filter(|e| e.path().extension().is_some_and(|ext| ext == "semanticdb"))
            .map(|e| e.into_path())
            .collect();
        all_files.extend(fallback_files);
    }

    Ok(DiscoveryResult {
        files: all_files,
        semanticdb_dirs: sdb_dirs,
    })
}
