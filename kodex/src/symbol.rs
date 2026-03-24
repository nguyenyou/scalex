/// SemanticDB symbol FQN utilities.
///
/// SemanticDB uses a specific symbol format:
/// - `/` separates packages: `scala/collection/`
/// - `#` separates class members: `scala/Option#get().`
/// - `.` separates object members: `scala/Predef.println().`
/// - `()` disambiguates overloaded methods: `apply(+1).`

/// Extract the owner symbol from a fully qualified SemanticDB symbol.
///
/// Examples:
/// - `scala/Option#get().` → `scala/Option#`
/// - `scala/Option#` → `scala/`
/// - `scala/` → `` (top-level package, no parent in symbol table)
/// - `local0` → ``
pub fn symbol_owner(fqn: &str) -> &str {
    if fqn.is_empty() {
        return "";
    }
    // Local symbols have no owner
    if fqn.starts_with("local") {
        return "";
    }
    let bytes = fqn.as_bytes();
    let len = bytes.len();
    let mut i = len;
    // Step 1: Strip the trailing descriptor character(s)
    // Symbols end with: `.` (method/object), `#` (class), `/` (package)
    // Methods may have `().` or `(+1).` before the final `.`
    if i > 0 && bytes[i - 1] == b'.' {
        i -= 1;
        // Check for method descriptor like `(...)` before the `.`
        if i > 0 && bytes[i - 1] == b')' {
            while i > 0 && bytes[i - 1] != b'(' {
                i -= 1;
            }
            if i > 0 {
                i -= 1; // skip `(`
            }
        }
    } else if i > 0 && matches!(bytes[i - 1], b'#' | b'/') {
        i -= 1;
    }
    // Step 2: Skip the name part backwards to the previous separator
    while i > 0 && !matches!(bytes[i - 1], b'/' | b'#' | b'.') {
        i -= 1;
    }
    &fqn[..i]
}

/// Extract the short display name from a SemanticDB symbol FQN.
///
/// Examples:
/// - `scala/Option#get().` → `get`
/// - `scala/Option#` → `Option`
/// - `scala/` → `scala`
pub fn symbol_display_name(fqn: &str) -> &str {
    if fqn.is_empty() {
        return "";
    }
    if fqn.starts_with("local") {
        return fqn;
    }
    let bytes = fqn.as_bytes();
    let len = bytes.len();
    let mut end = len;
    // Skip trailing `.`, `#`, `/`
    if end > 0 && matches!(bytes[end - 1], b'.' | b'#' | b'/') {
        end -= 1;
    }
    // Skip method descriptor `(...)` if present
    if end > 0 && bytes[end - 1] == b')' {
        while end > 0 && bytes[end - 1] != b'(' {
            end -= 1;
        }
        if end > 0 {
            end -= 1; // skip `(`
        }
        // Skip the trailing `.` before `(`
        if end > 0 && bytes[end - 1] == b'.' {
            end -= 1;
        }
    }
    // Find the start of the name
    let mut start = end;
    while start > 0 && !matches!(bytes[start - 1], b'/' | b'#' | b'.') {
        start -= 1;
    }
    &fqn[start..end]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_symbol_owner() {
        assert_eq!(symbol_owner("scala/Option#get()."), "scala/Option#");
        assert_eq!(symbol_owner("scala/Option#"), "scala/");
        assert_eq!(symbol_owner("scala/"), "");
        assert_eq!(symbol_owner("com/example/Foo.bar()."), "com/example/Foo.");
        assert_eq!(symbol_owner("local0"), "");
        assert_eq!(symbol_owner(""), "");
    }

    #[test]
    fn test_symbol_display_name() {
        assert_eq!(symbol_display_name("scala/Option#get()."), "get");
        assert_eq!(symbol_display_name("scala/Option#"), "Option");
        assert_eq!(symbol_display_name("scala/"), "scala");
        assert_eq!(symbol_display_name("com/example/Foo."), "Foo");
        assert_eq!(symbol_display_name("com/example/Foo#apply(+1)."), "apply");
        assert_eq!(symbol_display_name("local0"), "local0");
    }
}
