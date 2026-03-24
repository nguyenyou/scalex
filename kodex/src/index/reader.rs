use std::path::Path;
use anyhow::{Context, Result};
use memmap2::Mmap;
use rkyv::access;

use crate::model::{ArchivedKodexIndex, KODEX_INDEX_VERSION};

/// Memory-mapped, zero-copy index reader.
pub struct IndexReader {
    _mmap: Mmap,
    index: &'static ArchivedKodexIndex,
}

impl IndexReader {
    /// Open and mmap a kodex.idx file.
    ///
    /// # Safety
    /// The index file must have been written by a compatible version of kodex.
    /// We validate the version field after mapping.
    pub fn open(path: &Path) -> Result<Self> {
        let file = std::fs::File::open(path)
            .with_context(|| format!("Failed to open {}", path.display()))?;
        let mmap = unsafe { Mmap::map(&file) }
            .with_context(|| format!("Failed to mmap {}", path.display()))?;

        // Safety: we validate the archive and hold the mmap for the lifetime of IndexReader
        let index: &ArchivedKodexIndex = unsafe {
            access::<ArchivedKodexIndex, rkyv::rancor::Error>(&mmap)
                .map_err(|e| anyhow::anyhow!("Invalid index file: {e}"))?
        };

        // Extend lifetime to 'static — safe because _mmap is owned by IndexReader
        let index: &'static ArchivedKodexIndex = unsafe { &*(index as *const _) };

        if index.version != KODEX_INDEX_VERSION {
            anyhow::bail!(
                "Index version mismatch: expected {}, got {}. Re-run `kodex index`.",
                KODEX_INDEX_VERSION, index.version
            );
        }

        Ok(Self { _mmap: mmap, index })
    }

    pub fn index(&self) -> &ArchivedKodexIndex {
        self.index
    }
}
