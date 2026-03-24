use std::fs;
use std::path::Path;
use anyhow::{Context, Result};
use rkyv::to_bytes;

use crate::model::KodexIndex;

/// Serialize a KodexIndex to disk using rkyv.
pub fn write_index(index: &KodexIndex, path: &Path) -> Result<()> {
    let bytes = to_bytes::<rkyv::rancor::Error>(index)
        .map_err(|e| anyhow::anyhow!("rkyv serialization failed: {e}"))?;

    // Atomic write: write to temp file, then rename
    let tmp = path.with_extension("idx.tmp");
    fs::write(&tmp, &bytes)
        .with_context(|| format!("Failed to write {}", tmp.display()))?;
    fs::rename(&tmp, path)
        .with_context(|| format!("Failed to rename to {}", path.display()))?;

    eprintln!("Index size: {} bytes ({:.1} MB)",
        bytes.len(), bytes.len() as f64 / 1_048_576.0);
    Ok(())
}
