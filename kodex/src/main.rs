mod model;
mod symbol;
mod ingest;
mod index;
mod query;

use std::path::Path;
use std::time::Instant;
use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "kodex", version, about = "Compiler-precise Scala code intelligence")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Build kodex.idx from a compiled project's SemanticDB data
    Index {
        /// Workspace root (must contain Mill out/ directory)
        #[arg(long, default_value = ".")]
        root: String,
    },
    /// Find symbol definitions
    Def {
        query: String,
        #[arg(long, default_value = "50")]
        limit: usize,
        #[arg(long)]
        kind: Option<String>,
        #[arg(short, long)]
        verbose: bool,
        /// Path to kodex.idx
        #[arg(long)]
        idx: Option<String>,
    },
    /// Find all references to a symbol
    Refs {
        query: String,
        #[arg(long, default_value = "50")]
        limit: usize,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Who calls this symbol? (trait-aware)
    Callers {
        query: String,
        #[arg(long, default_value = "50")]
        limit: usize,
        #[arg(long)]
        kind: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// What does this symbol call?
    Callees {
        query: String,
        #[arg(long, default_value = "50")]
        limit: usize,
        #[arg(long)]
        kind: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Inheritance tree (supertypes and subtypes)
    Hierarchy {
        query: String,
        #[arg(long)]
        kind: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Members of a class, trait, or object
    Members {
        query: String,
        #[arg(long, default_value = "50")]
        limit: usize,
        #[arg(long)]
        kind: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Override chain for a symbol
    Overrides {
        query: String,
        #[arg(long)]
        kind: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Type signature of a symbol
    Type {
        query: String,
        #[arg(long)]
        kind: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Project overview: modules, hub types, entry points
    Orient {
        #[arg(long)]
        idx: Option<String>,
    },
    /// Complete picture of a type or method (members + callers + callees + related)
    Explore {
        query: String,
        #[arg(long, default_value = "20")]
        limit: usize,
        #[arg(long)]
        kind: Option<String>,
        /// Comma-separated patterns to exclude (matches FQN, name, or owner)
        #[arg(long)]
        exclude: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Downstream call tree with module boundaries
    Flow {
        query: String,
        #[arg(long, default_value = "3")]
        depth: usize,
        #[arg(long)]
        kind: Option<String>,
        /// Comma-separated patterns to exclude (matches FQN, name, or owner)
        #[arg(long)]
        exclude: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// What breaks if this symbol changes
    Impact {
        query: String,
        #[arg(long)]
        kind: Option<String>,
        /// Comma-separated patterns to exclude (matches FQN, name, or owner)
        #[arg(long)]
        exclude: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
    /// Shortest call path between two symbols
    Trace {
        from: String,
        to: String,
        #[arg(long)]
        kind: Option<String>,
        #[arg(long)]
        idx: Option<String>,
    },
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    match cli.command {
        Command::Index { root } => {
            let t0 = Instant::now();
            eprintln!("Indexing workspace: {root}");

            let discoveries = ingest::discovery::discover_semanticdb(&root)?;
            let t_disc = t0.elapsed();
            eprintln!("Found {} .semanticdb files ({:.1}s)", discoveries.files.len(), t_disc.as_secs_f64());

            let documents = ingest::semanticdb::load_all(&discoveries.files)?;
            let t_parse = t0.elapsed();
            let total_symbols: usize = documents.iter().map(|d| d.symbols.len()).sum();
            let total_occs: usize = documents.iter().map(|d| d.occurrences.len()).sum();
            eprintln!("Parsed {} documents, {} symbols, {} occurrences ({:.1}s)",
                documents.len(), total_symbols, total_occs, t_parse.as_secs_f64());

            let built = ingest::merge::build_index(documents);
            let t_merge = t0.elapsed();
            eprintln!("Index built: {} symbols, {} files ({:.1}s)",
                built.symbols.len(), built.files.len(), t_merge.as_secs_f64());

            let scalex_dir = std::path::Path::new(&root).join(".scalex");
            std::fs::create_dir_all(&scalex_dir)?;
            let idx_path = scalex_dir.join("kodex.idx");
            index::writer::write_index(&built, &idx_path)?;
            let t_total = t0.elapsed();
            eprintln!("Total: {:.1}s", t_total.as_secs_f64());

            Ok(())
        }

        Command::Def { query, limit, kind, verbose, idx } => {
            let reader = open_index(idx.as_deref())?;
            let index = reader.index();

            let matches = query::symbol::resolve_symbols(index, &query);
            if matches.is_empty() {
                println!("Not found: No symbol found matching '{query}'");
                return Ok(());
            }
            let filtered = query::symbol::filter_by_kind(index, &matches, kind.as_deref());
            let candidates = if !filtered.is_empty() { filtered } else { matches };

            if candidates.len() == 1 {
                print!("{}", query::format::format_symbol_detail(index, candidates[0], verbose));
            } else {
                println!("{} symbols matching '{query}'", candidates.len());
                let effective_limit = if limit == 0 { candidates.len() } else { limit };
                for s in candidates.iter().take(effective_limit) {
                    if verbose {
                        print!("{}", query::format::format_symbol_detail(index, s, true));
                    } else {
                        println!("{}", query::format::format_symbol_line(index, s));
                    }
                }
                if candidates.len() > effective_limit {
                    println!("... and {} more (use --limit 0 for all)", candidates.len() - effective_limit);
                }
            }
            Ok(())
        }

        Command::Refs { query, limit, idx } => {
            let reader = open_index(idx.as_deref())?;
            let effective_limit = if limit == 0 { usize::MAX } else { limit };
            query::refs::cmd_refs(reader.index(), &query, effective_limit);
            Ok(())
        }

        Command::Callers { query, limit, kind, idx } => {
            let reader = open_index(idx.as_deref())?;
            let effective_limit = if limit == 0 { usize::MAX } else { limit };
            query::graph::cmd_callers(reader.index(), &query, effective_limit, kind.as_deref());
            Ok(())
        }

        Command::Callees { query, limit, kind, idx } => {
            let reader = open_index(idx.as_deref())?;
            let effective_limit = if limit == 0 { usize::MAX } else { limit };
            query::graph::cmd_callees(reader.index(), &query, effective_limit, kind.as_deref());
            Ok(())
        }

        Command::Hierarchy { query, kind, idx } => {
            let reader = open_index(idx.as_deref())?;
            query::graph::cmd_hierarchy(reader.index(), &query, kind.as_deref());
            Ok(())
        }

        Command::Members { query, limit, kind, idx } => {
            let reader = open_index(idx.as_deref())?;
            let effective_limit = if limit == 0 { usize::MAX } else { limit };
            query::graph::cmd_members(reader.index(), &query, effective_limit, kind.as_deref());
            Ok(())
        }

        Command::Overrides { query, kind, idx } => {
            let reader = open_index(idx.as_deref())?;
            query::graph::cmd_overrides(reader.index(), &query, kind.as_deref());
            Ok(())
        }

        Command::Type { query, kind, idx } => {
            let reader = open_index(idx.as_deref())?;
            let index = reader.index();
            let sym = match query::symbol::resolve_one(index, &query, kind.as_deref()) {
                Some(s) => s,
                None => {
                    println!("Not found: No symbol found matching '{query}'");
                    return Ok(());
                }
            };
            let name = query::s(index, sym.name);
            let sig = query::s(index, sym.type_signature);
            if !sig.is_empty() {
                println!("{name}: {sig}");
            } else {
                println!("Not found: No type information available for '{name}'");
            }
            Ok(())
        }

        Command::Orient { idx } => {
            let reader = open_index(idx.as_deref())?;
            query::commands::orient::cmd_orient(reader.index());
            Ok(())
        }

        Command::Explore { query, limit, kind, exclude, idx } => {
            let reader = open_index(idx.as_deref())?;
            let effective_limit = if limit == 0 { usize::MAX } else { limit };
            let excl = parse_exclude(&exclude);
            query::commands::explore::cmd_explore(reader.index(), &query, effective_limit, kind.as_deref(), &excl);
            Ok(())
        }

        Command::Flow { query, depth, kind, exclude, idx } => {
            let reader = open_index(idx.as_deref())?;
            let excl = parse_exclude(&exclude);
            query::commands::flow::cmd_flow(reader.index(), &query, depth, kind.as_deref(), &excl);
            Ok(())
        }

        Command::Impact { query, kind, exclude, idx } => {
            let reader = open_index(idx.as_deref())?;
            let excl = parse_exclude(&exclude);
            query::commands::impact::cmd_impact(reader.index(), &query, kind.as_deref(), &excl);
            Ok(())
        }

        Command::Trace { from, to, kind, idx } => {
            let reader = open_index(idx.as_deref())?;
            query::commands::trace::cmd_trace(reader.index(), &from, &to, kind.as_deref());
            Ok(())
        }
    }
}

fn open_index(idx_path: Option<&str>) -> anyhow::Result<index::reader::IndexReader> {
    let path = idx_path
        .map(|p| std::path::PathBuf::from(p))
        .unwrap_or_else(|| Path::new(".scalex/kodex.idx").to_path_buf());
    index::reader::IndexReader::open(&path)
}

fn parse_exclude(exclude: &Option<String>) -> Vec<String> {
    exclude.as_deref()
        .map(|s| s.split(',').map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect())
        .unwrap_or_default()
}
