fn main() {
    prost_build::Config::new()
        .compile_protos(&["proto/semanticdb.proto"], &["proto/"])
        .expect("Failed to compile semanticdb.proto");
}
