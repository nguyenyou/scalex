use crate::model::{ArchivedKodexIndex, ArchivedSymbol, ArchivedSymbolKind, ArchivedEdgeList};
use super::{s, id};

/// Find symbols matching a query string. Uses trigram + hash indexes for speed.
/// Tries in order:
/// 1. Exact FQN match (via trigram narrowing)
/// 2. Suffix match on FQN (via trigram narrowing)
/// 3. Exact display name match (via hash index — O(1))
/// 4. Substring match on display name (via trigram intersection)
pub fn resolve_symbols<'a>(index: &'a ArchivedKodexIndex, query: &str) -> Vec<&'a ArchivedSymbol> {
    // 1. Exact FQN match — use trigram index to narrow candidates
    let candidates = trigram_candidates(index, query);
    if let Some(ref cands) = candidates {
        let exact: Vec<_> = cands.iter()
            .filter(|&&sid| s(index, index.symbols[sid as usize].fqn) == query)
            .map(|&sid| &index.symbols[sid as usize])
            .collect();
        if !exact.is_empty() { return exact; }
    } else {
        // Fallback for very short queries (< 3 chars): linear scan
        let exact: Vec<_> = index.symbols.iter()
            .filter(|sym| s(index, sym.fqn) == query)
            .collect();
        if !exact.is_empty() { return exact; }
    }

    // 2. Suffix match on FQN
    if let Some(ref cands) = candidates {
        let suffix: Vec<_> = cands.iter()
            .filter(|&&sid| {
                let fqn = s(index, index.symbols[sid as usize].fqn);
                fqn.ends_with(query) || fqn.ends_with(&format!("{query}.")) || fqn.ends_with(&format!("{query}#"))
            })
            .map(|&sid| &index.symbols[sid as usize])
            .collect();
        if !suffix.is_empty() { return suffix; }
    } else {
        let suffix: Vec<_> = index.symbols.iter()
            .filter(|sym| {
                let fqn = s(index, sym.fqn);
                fqn.ends_with(query) || fqn.ends_with(&format!("{query}.")) || fqn.ends_with(&format!("{query}#"))
            })
            .collect();
        if !suffix.is_empty() { return suffix; }
    }

    // 3. Exact display name match — use hash index for O(1)
    let by_hash = hash_lookup(index, query);
    if !by_hash.is_empty() { return by_hash; }

    // 4. Substring match on display name via trigram intersection
    if let Some(ref cands) = candidates {
        let query_lower = query.to_lowercase();
        let substr: Vec<_> = cands.iter()
            .filter(|&&sid| s(index, index.symbols[sid as usize].name).to_lowercase().contains(&query_lower))
            .map(|&sid| &index.symbols[sid as usize])
            .collect();
        if !substr.is_empty() { return substr; }
    }

    vec![]
}

/// Use the hash index for O(1) exact display name lookup.
fn hash_lookup<'a>(index: &'a ArchivedKodexIndex, query: &str) -> Vec<&'a ArchivedSymbol> {
    let hash_size: u32 = index.name_hash_size.into();
    if hash_size == 0 { return vec![]; }
    let bucket_idx = name_hash_query(query, hash_size);
    if bucket_idx as usize >= index.name_hash_buckets.len() { return vec![]; }
    let bucket = &index.name_hash_buckets[bucket_idx as usize];
    let query_lower = query.to_lowercase();
    bucket.symbol_ids.iter()
        .filter(|sid| {
            let sid_val: u32 = (**sid).into();
            s(index, index.symbols[sid_val as usize].name).to_lowercase() == query_lower
        })
        .map(|sid| { let sid_val: u32 = (*sid).into(); &index.symbols[sid_val as usize] })
        .collect()
}

/// Use trigram index to find candidate symbol IDs matching a query.
/// Returns None if query is too short for trigrams (< 3 chars).
fn trigram_candidates(index: &ArchivedKodexIndex, query: &str) -> Option<Vec<u32>> {
    let bytes = query.as_bytes();
    if bytes.len() < 3 { return None; }

    // Extract trigrams from query
    let mut query_trigrams: Vec<u32> = Vec::new();
    for i in 0..bytes.len() - 2 {
        query_trigrams.push(trigram_key_query(bytes[i], bytes[i + 1], bytes[i + 2]));
    }
    query_trigrams.sort_unstable();
    query_trigrams.dedup();

    // Intersect posting lists
    let mut result: Option<Vec<u32>> = None;
    for &tri_key in &query_trigrams {
        // Binary search in sorted trigram entries
        let posting = match index.name_trigrams.binary_search_by_key(&tri_key, |entry| entry.key.into()) {
            Ok(idx) => {
                index.name_trigrams[idx].symbol_ids.iter().map(|v| (*v).into()).collect::<Vec<u32>>()
            }
            Err(_) => return Some(vec![]), // trigram not found → no matches
        };
        result = Some(match result {
            None => posting,
            Some(prev) => intersect_sorted(&prev, &posting),
        });
        // Early exit if intersection is empty
        if result.as_ref().map_or(false, |r| r.is_empty()) {
            return Some(vec![]);
        }
    }
    result
}

/// Intersect two sorted Vec<u32>.
fn intersect_sorted(a: &[u32], b: &[u32]) -> Vec<u32> {
    let mut result = Vec::new();
    let (mut i, mut j) = (0, 0);
    while i < a.len() && j < b.len() {
        match a[i].cmp(&b[j]) {
            std::cmp::Ordering::Less => i += 1,
            std::cmp::Ordering::Greater => j += 1,
            std::cmp::Ordering::Equal => {
                result.push(a[i]);
                i += 1;
                j += 1;
            }
        }
    }
    result
}

fn trigram_key_query(a: u8, b: u8, c: u8) -> u32 {
    (a.to_ascii_lowercase() as u32)
        | ((b.to_ascii_lowercase() as u32) << 8)
        | ((c.to_ascii_lowercase() as u32) << 16)
}

fn name_hash_query(s: &str, bucket_count: u32) -> u32 {
    let mut h: u32 = 0;
    for b in s.as_bytes() {
        h = h.wrapping_mul(31).wrapping_add(b.to_ascii_lowercase() as u32);
    }
    h % bucket_count
}

/// Resolve to a single symbol, printing disambiguation to stderr if ambiguous.
pub fn resolve_one<'a>(
    index: &'a ArchivedKodexIndex,
    query: &str,
    kind_filter: Option<&str>,
) -> Option<&'a ArchivedSymbol> {
    let matches = resolve_symbols(index, query);
    if matches.is_empty() {
        return None;
    }
    let filtered = filter_by_kind(index, &matches, kind_filter);
    let candidates = if !filtered.is_empty() { filtered } else { matches };

    if candidates.len() > 1 {
        eprintln!("Ambiguous: {} symbols match '{}'. Using {}", candidates.len(), query, s(index, candidates[0].fqn));
        eprintln!("  Disambiguate with FQN or --kind. Candidates:");
        for sym in candidates.iter().take(5) {
            eprintln!("    {} {}", kind_str(&sym.kind), s(index, sym.fqn));
        }
        if candidates.len() > 5 {
            eprintln!("    ... and {} more", candidates.len() - 5);
        }
    }
    Some(candidates[0])
}

pub fn filter_by_kind<'a>(
    _index: &'a ArchivedKodexIndex,
    symbols: &[&'a ArchivedSymbol],
    kind_filter: Option<&str>,
) -> Vec<&'a ArchivedSymbol> {
    let Some(k) = kind_filter else { return symbols.to_vec() };
    let k_lower = k.to_lowercase();
    symbols.iter()
        .filter(|sym| kind_str(&sym.kind) == k_lower)
        .copied()
        .collect()
}

pub fn kind_str(kind: &ArchivedSymbolKind) -> &'static str {
    match *kind {
        ArchivedSymbolKind::Class => "class",
        ArchivedSymbolKind::Trait => "trait",
        ArchivedSymbolKind::Object => "object",
        ArchivedSymbolKind::Method => "method",
        ArchivedSymbolKind::Field => "field",
        ArchivedSymbolKind::Type => "type",
        ArchivedSymbolKind::Constructor => "constructor",
        ArchivedSymbolKind::Parameter => "parameter",
        ArchivedSymbolKind::TypeParameter => "typeparameter",
        ArchivedSymbolKind::Package => "package",
        ArchivedSymbolKind::PackageObject => "packageobject",
        ArchivedSymbolKind::Macro => "macro",
        ArchivedSymbolKind::Local => "local",
        ArchivedSymbolKind::Interface => "interface",
        ArchivedSymbolKind::SelfParameter => "selfparameter",
        _ => "unknown",
    }
}

/// Find edges from a given node in an edge list.
pub fn edges_from(edge_lists: &[ArchivedEdgeList], from_id: u32) -> Vec<u32> {
    for el in edge_lists {
        let el_from: u32 = el.from.into();
        if el_from == from_id {
            return el.to.iter().map(|v| (*v).into()).collect();
        }
    }
    vec![]
}
