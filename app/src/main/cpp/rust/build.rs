use std::path::PathBuf;

fn main() {
    let root = PathBuf::from("vendor/libwebp");
    if !root.exists() {
        panic!("vendor/libwebp is missing. Run ./build-rust.sh (it fetches libwebp automatically).");
    }

    let mut b = cc::Build::new();
    b.include(&root).include(root.join("src"));
    b.warnings(false);
    b.flag_if_supported("-fno-strict-aliasing");

    // Arch-specific files inside these dirs are guarded by their own WEBP_HAVE_*
    // macros, so compiling all of them is safe for both arm64 and x86_64.
    for dir in [
        "src/dec", "src/enc", "src/dsp", "src/utils", "src/mux", "src/demux", "sharpyuv",
    ] {
        let d = root.join(dir);
        let rd = match std::fs::read_dir(&d) {
            Ok(rd) => rd,
            Err(e) => panic!("cannot read {}: {e}", d.display()),
        };
        for entry in rd.flatten() {
            let p = entry.path();
            if p.extension().map(|e| e == "c").unwrap_or(false) {
                b.file(p);
            }
        }
    }
    b.file("shim/anim_shim.c");
    b.compile("webp_anim");

    println!("cargo:rerun-if-changed=shim/anim_shim.c");
    println!("cargo:rerun-if-changed=build.rs");
}