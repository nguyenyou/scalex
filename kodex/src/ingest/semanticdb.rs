use std::fs;
use std::path::PathBuf;
use anyhow::Result;
use prost::Message;
use rayon::prelude::*;

use crate::model::{
    proto, IntermediateDoc, IntermediateSymbol, IntermediateOccurrence,
    SymbolKind, Access, ReferenceRole,
};
use crate::symbol::symbol_owner;
use crate::ingest::printer;

/// Load and convert all .semanticdb files in parallel.
pub fn load_all(files: &[PathBuf]) -> Result<Vec<IntermediateDoc>> {
    let docs: Vec<IntermediateDoc> = files.par_iter()
        .filter_map(|path| {
            let bytes = fs::read(path).ok()?;
            let text_docs = proto::TextDocuments::decode(bytes.as_slice()).ok()?;
            Some(text_docs.documents.into_iter().filter_map(convert_document).collect::<Vec<_>>())
        })
        .flatten()
        .collect();
    Ok(docs)
}

fn convert_document(doc: proto::TextDocument) -> Option<IntermediateDoc> {
    let uri = doc.uri.clone();
    if uri.is_empty() {
        return None;
    }

    // Build a local symtab for the printer (symbol FQN → SymbolInformation)
    let symtab: rustc_hash::FxHashMap<String, &proto::SymbolInformation> =
        doc.symbols.iter().map(|s| (s.symbol.clone(), s)).collect();

    let symbols: Vec<IntermediateSymbol> = doc.symbols.iter().map(|info| {
        let owner = symbol_owner(&info.symbol).to_string();
        let parents = extract_parent_symbols(&info.signature);
        let sig = printer::print_info(info, &symtab);
        let annotations = info.annotations.iter()
            .filter_map(|a| extract_type_symbol(a.tpe.as_ref()?))
            .collect();

        IntermediateSymbol {
            fqn: info.symbol.clone(),
            display_name: info.display_name.clone(),
            kind: SymbolKind::from_proto(info.kind),
            properties: info.properties as u32,
            owner,
            source_uri: uri.clone(),
            signature: sig,
            parents,
            overridden_symbols: info.overridden_symbols.clone(),
            annotations,
            access: Access::from_proto(&info.access),
        }
    }).collect();

    let occurrences: Vec<IntermediateOccurrence> = doc.occurrences.iter().map(|occ| {
        let (sl, sc, el, ec) = match &occ.range {
            Some(r) => (r.start_line as u32, r.start_character as u32, r.end_line as u32, r.end_character as u32),
            None => (0, 0, 0, 0),
        };
        IntermediateOccurrence {
            file: uri.clone(),
            symbol: occ.symbol.clone(),
            role: ReferenceRole::from_proto(occ.role),
            start_line: sl,
            start_col: sc,
            end_line: el,
            end_col: ec,
        }
    }).collect();

    Some(IntermediateDoc {
        uri,
        md5: doc.md5,
        symbols,
        occurrences,
    })
}

/// Extract parent type symbols from a ClassSignature.
fn extract_parent_symbols(sig: &Option<proto::Signature>) -> Vec<String> {
    let Some(sig) = sig else { return vec![] };
    let Some(ref sv) = sig.sealed_value else { return vec![] };
    match sv {
        proto::signature::SealedValue::ClassSignature(cs) => {
            cs.parents.iter()
                .filter_map(|t| extract_type_symbol_from_type(t))
                .collect()
        }
        _ => vec![],
    }
}

fn extract_type_symbol_from_type(tpe: &proto::Type) -> Option<String> {
    let sv = tpe.sealed_value.as_ref()?;
    match sv {
        proto::r#type::SealedValue::TypeRef(tr) => {
            if !tr.symbol.is_empty() { Some(tr.symbol.clone()) } else { None }
        }
        proto::r#type::SealedValue::SingleType(st) => {
            if !st.symbol.is_empty() { Some(st.symbol.clone()) } else { None }
        }
        proto::r#type::SealedValue::ThisType(tt) => {
            if !tt.symbol.is_empty() { Some(tt.symbol.clone()) } else { None }
        }
        proto::r#type::SealedValue::AnnotatedType(at) => {
            at.tpe.as_ref().and_then(|t| extract_type_symbol_from_type(t))
        }
        _ => None,
    }
}

fn extract_type_symbol(tpe: &proto::Type) -> Option<String> {
    extract_type_symbol_from_type(tpe)
}
