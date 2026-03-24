use std::collections::VecDeque;
use crate::model::ArchivedKodexIndex;
use crate::query::{s, id};
use crate::query::symbol::{resolve_one, edges_from, kind_str};

/// BFS shortest call path between two symbols.
pub fn cmd_trace(index: &ArchivedKodexIndex, from_query: &str, to_query: &str, kind_filter: Option<&str>) {
    let from_sym = match resolve_one(index, from_query, kind_filter) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{from_query}'"); return; }
    };
    let to_sym = match resolve_one(index, to_query, kind_filter) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{to_query}'"); return; }
    };

    let from_id: u32 = from_sym.id.into();
    let to_id: u32 = to_sym.id.into();
    let from_name = s(index, from_sym.name);
    let to_name = s(index, to_sym.name);

    if from_id == to_id {
        println!("Call path (0 hops): {from_name} = {to_name}");
        return;
    }

    // BFS over call_graph_forward from `from` looking for `to`
    let mut queue: VecDeque<u32> = VecDeque::new();
    let mut parent: rustc_hash::FxHashMap<u32, u32> = rustc_hash::FxHashMap::default();
    queue.push_back(from_id);
    parent.insert(from_id, u32::MAX);

    let max_visited = 100_000;
    let mut found = false;

    while let Some(current) = queue.pop_front() {
        if current == to_id { found = true; break; }
        if parent.len() > max_visited { break; }

        let callees = edges_from(&index.call_graph_forward, current);
        for cid in callees {
            if !parent.contains_key(&cid) {
                parent.insert(cid, current);
                queue.push_back(cid);
            }
        }
    }

    if !found {
        println!("No call path found from '{from_name}' to '{to_name}'");
        return;
    }

    // Reconstruct path
    let mut path = Vec::new();
    let mut cur = to_id;
    while cur != u32::MAX {
        path.push(cur);
        cur = parent[&cur];
    }
    path.reverse();

    println!("Call path ({} hops):", path.len() - 1);
    for (i, &sid) in path.iter().enumerate() {
        let sym = &index.symbols[sid as usize];
        let name = s(index, sym.name);
        let owner_id: u32 = sym.owner.into();
        let owner = if owner_id != u32::MAX && (owner_id as usize) < index.symbols.len() {
            format!("{}.", s(index, index.symbols[owner_id as usize].name))
        } else { String::new() };

        if i == 0 {
            println!("  {owner}{name}");
        } else {
            println!("  → {owner}{name}");
        }
    }
}
