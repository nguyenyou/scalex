/// Generated protobuf types from semanticdb.proto
pub mod proto {
    include!(concat!(env!("OUT_DIR"), "/scala.meta.internal.semanticdb.rs"));
}

// ── rkyv index types ────────────────────────────────────────────────────────

use rkyv::{Archive, Serialize, Deserialize};

/// Newtype for an index into the string table.
pub type StringId = u32;

/// Sentinel value meaning "no symbol" / "no string".
pub const NO_STRING: StringId = u32::MAX;

#[derive(Archive, Serialize, Deserialize, Debug)]
#[rkyv(compare(PartialEq))]
pub struct KodexIndex {
    pub version: u32,
    /// Deduplicated string table (insertion-ordered). All other string fields are indices here.
    pub strings: Vec<String>,
    /// Source files in the project.
    pub files: Vec<FileEntry>,
    /// All symbols (classes, traits, defs, vals, types, enums, givens, etc.)
    pub symbols: Vec<Symbol>,
    /// symbol_id → [reference locations]
    pub references: Vec<ReferenceList>,
    /// caller_symbol_id → [callee_symbol_id]  (forward call graph)
    pub call_graph_forward: Vec<EdgeList>,
    /// callee_symbol_id → [caller_symbol_id]  (reverse call graph)
    pub call_graph_reverse: Vec<EdgeList>,
    /// parent_symbol_id → [child_symbol_id]  (inheritance: who extends me?)
    pub inheritance_forward: Vec<EdgeList>,
    /// child_symbol_id → [parent_symbol_id]  (inheritance: what do I extend?)
    pub inheritance_reverse: Vec<EdgeList>,
    /// owner_symbol_id → [member_symbol_id]
    pub members: Vec<EdgeList>,
    /// base_symbol_id → [overrider_symbol_id]
    pub overrides: Vec<EdgeList>,
    /// Mill module metadata.
    pub modules: Vec<MillModule>,
    /// Trigram index over symbol names for fast substring search.
    /// name_trigrams[i] = (trigram_key, [symbol_ids...])
    /// trigram_key is a u32 encoding of 3 lowercase ASCII bytes.
    pub name_trigrams: Vec<TrigramEntry>,
    /// HashMap-style index: name_hash → [symbol_ids...]
    /// For O(1) exact display name lookup.
    pub name_hash_buckets: Vec<HashBucket>,
    /// Number of hash buckets (for modulo).
    pub name_hash_size: u32,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct TrigramEntry {
    /// Trigram key: 3 lowercase bytes packed into u32 (b0 | b1<<8 | b2<<16)
    pub key: u32,
    /// Symbol IDs that contain this trigram in their display name.
    pub symbol_ids: Vec<u32>,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct HashBucket {
    /// Symbol IDs whose display name (lowercased) hashes to this bucket.
    pub symbol_ids: Vec<u32>,
}

pub const KODEX_INDEX_VERSION: u32 = 3;

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct FileEntry {
    /// Index into strings table — source file path (relative URI).
    pub path: StringId,
    /// Which Mill module this file belongs to. u32::MAX if unknown.
    pub module_id: u32,
    /// Pre-classified at index time.
    pub is_test: bool,
    /// Pre-classified at index time.
    pub is_generated: bool,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct MillModule {
    pub name: StringId,
    pub source_paths: Vec<StringId>,
    pub file_count: u32,
    pub symbol_count: u32,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct Symbol {
    pub id: u32,
    /// Short display name (e.g. "apply").
    pub name: StringId,
    /// Fully qualified name (e.g. "com/example/Foo#apply().").
    pub fqn: StringId,
    pub kind: SymbolKind,
    /// Index into files table.
    pub file_id: u32,
    pub line: u32,
    pub col: u32,
    /// Pretty-printed type signature.
    pub type_signature: StringId,
    /// Parent symbol id (enclosing class/object). u32::MAX if none.
    pub owner: u32,
    /// Properties bitmask (abstract, final, sealed, etc.)
    pub properties: u32,
    pub access: Access,
    /// Parent type FQNs (from ClassSignature parents). Stored as StringIds.
    /// Includes both in-index and external (stdlib) parents.
    pub parents: Vec<StringId>,
    /// Overridden symbol FQNs. Stored as StringIds.
    pub overridden_symbols: Vec<StringId>,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[rkyv(compare(PartialEq))]
#[repr(u8)]
pub enum SymbolKind {
    Unknown = 0,
    Class = 1,
    Trait = 2,
    Object = 3,
    Method = 4,
    Field = 5,
    Type = 6,
    Constructor = 7,
    Parameter = 8,
    TypeParameter = 9,
    Package = 10,
    PackageObject = 11,
    Macro = 12,
    Local = 13,
    Interface = 14,
    SelfParameter = 15,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[rkyv(compare(PartialEq))]
#[repr(u8)]
pub enum Access {
    Public = 0,
    Private = 1,
    PrivateThis = 2,
    PrivateWithin = 3,
    Protected = 4,
    ProtectedThis = 5,
    ProtectedWithin = 6,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct Reference {
    pub file_id: u32,
    pub line: u32,
    pub col: u32,
    pub role: ReferenceRole,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[rkyv(compare(PartialEq))]
#[repr(u8)]
pub enum ReferenceRole {
    Unknown = 0,
    Definition = 1,
    Reference = 2,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct ReferenceList {
    pub symbol_id: u32,
    pub refs: Vec<Reference>,
}

#[derive(Archive, Serialize, Deserialize, Debug, Clone)]
#[rkyv(compare(PartialEq))]
pub struct EdgeList {
    pub from: u32,
    pub to: Vec<u32>,
}

// ── Intermediate types (used during index build, not serialized) ────────────

/// A parsed SemanticDB document, before merging into the index.
pub struct IntermediateDoc {
    pub uri: String,
    pub md5: String,
    pub symbols: Vec<IntermediateSymbol>,
    pub occurrences: Vec<IntermediateOccurrence>,
}

pub struct IntermediateSymbol {
    pub fqn: String,
    pub display_name: String,
    pub kind: SymbolKind,
    pub properties: u32,
    pub owner: String,
    pub source_uri: String,
    pub signature: String,
    pub parents: Vec<String>,
    pub overridden_symbols: Vec<String>,
    pub annotations: Vec<String>,
    pub access: Access,
}

pub struct IntermediateOccurrence {
    pub file: String,
    pub symbol: String,
    pub role: ReferenceRole,
    pub start_line: u32,
    pub start_col: u32,
    pub end_line: u32,
    pub end_col: u32,
}

// ── Property bitmask constants ──────────────────────────────────────────────

pub mod props {
    pub const ABSTRACT: u32 = 0x4;
    pub const FINAL: u32 = 0x8;
    pub const SEALED: u32 = 0x10;
    pub const IMPLICIT: u32 = 0x20;
    pub const LAZY: u32 = 0x40;
    pub const CASE: u32 = 0x80;
    pub const COVARIANT: u32 = 0x100;
    pub const CONTRAVARIANT: u32 = 0x200;
    pub const VAL: u32 = 0x400;
    pub const VAR: u32 = 0x800;
    pub const STATIC: u32 = 0x1000;
    pub const PRIMARY: u32 = 0x2000;
    pub const ENUM: u32 = 0x4000;
    pub const DEFAULT: u32 = 0x8000;
    pub const GIVEN: u32 = 0x10000;
    pub const INLINE: u32 = 0x20000;
    pub const OPEN: u32 = 0x40000;
    pub const TRANSPARENT: u32 = 0x80000;
    pub const INFIX: u32 = 0x100000;
    pub const OPAQUE: u32 = 0x200000;
    pub const OVERRIDE: u32 = 0x400000;
}

// ── Conversions from proto enums ────────────────────────────────────────────

impl SymbolKind {
    pub fn from_proto(kind: i32) -> Self {
        use proto::symbol_information::Kind;
        match Kind::try_from(kind) {
            Ok(Kind::Class) => Self::Class,
            Ok(Kind::Trait) => Self::Trait,
            Ok(Kind::Object) => Self::Object,
            Ok(Kind::Method) => Self::Method,
            Ok(Kind::Field) => Self::Field,
            Ok(Kind::Type) => Self::Type,
            Ok(Kind::Constructor) => Self::Constructor,
            Ok(Kind::Parameter) => Self::Parameter,
            Ok(Kind::TypeParameter) => Self::TypeParameter,
            Ok(Kind::Package) => Self::Package,
            Ok(Kind::PackageObject) => Self::PackageObject,
            Ok(Kind::Macro) => Self::Macro,
            Ok(Kind::Local) => Self::Local,
            Ok(Kind::Interface) => Self::Interface,
            Ok(Kind::SelfParameter) => Self::SelfParameter,
            _ => Self::Unknown,
        }
    }
}

impl Access {
    pub fn from_proto(access: &Option<proto::Access>) -> Self {
        match access {
            None => Self::Public,
            Some(a) => match &a.sealed_value {
                None => Self::Public,
                Some(proto::access::SealedValue::PublicAccess(_)) => Self::Public,
                Some(proto::access::SealedValue::PrivateAccess(_)) => Self::Private,
                Some(proto::access::SealedValue::PrivateThisAccess(_)) => Self::PrivateThis,
                Some(proto::access::SealedValue::PrivateWithinAccess(_)) => Self::PrivateWithin,
                Some(proto::access::SealedValue::ProtectedAccess(_)) => Self::Protected,
                Some(proto::access::SealedValue::ProtectedThisAccess(_)) => Self::ProtectedThis,
                Some(proto::access::SealedValue::ProtectedWithinAccess(_)) => Self::ProtectedWithin,
            },
        }
    }
}

impl ReferenceRole {
    pub fn from_proto(role: i32) -> Self {
        use proto::symbol_occurrence::Role;
        match Role::try_from(role) {
            Ok(Role::Definition) => Self::Definition,
            Ok(Role::Reference) => Self::Reference,
            _ => Self::Unknown,
        }
    }
}
