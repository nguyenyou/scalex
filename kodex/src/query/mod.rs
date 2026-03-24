pub mod symbol;
pub mod refs;
pub mod graph;
pub mod format;
pub mod filter;
pub mod commands;

use crate::model::ArchivedKodexIndex;

/// Convert an archived u32 (u32_le) to a native usize for indexing.
#[inline]
pub fn id(v: impl Into<u32>) -> usize {
    v.into() as usize
}

/// Get the string for a StringId from the archived index.
#[inline]
pub fn s(index: &ArchivedKodexIndex, string_id: impl Into<u32>) -> &str {
    &index.strings[string_id.into() as usize]
}
