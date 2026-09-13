#!/usr/bin/env bash
set -euo pipefail

case "${1:-./scalex}" in
  /*) binary="${1:-./scalex}" ;;
  *) binary="$PWD/${1:-./scalex}" ;;
esac
workspace=$(mktemp -d)
trap 'rm -rf "$workspace"' EXIT

cat > "$workspace/Counter.java" <<'JAVA'
package example;

/** A counter with both primitive and generic fields. */
public class Counter<T> {
    private int value;
    private java.util.List<T> items;
    public static final int DEFAULT = 0;
    public Counter(int initial) { value = initial; }
    public int current() { return value; }
    public void exercise() {
        int[] numbers = new int[2];
        Object action = (Runnable & java.io.Serializable) () -> {};
        try { throw new java.io.IOException(); }
        catch (java.io.IOException | IllegalArgumentException error) {}
    }
}
JAVA
git -C "$workspace" init -q
git -C "$workspace" add Counter.java

stats=$("$binary" index "$workspace" --json)
if ! grep -q '"parseFailures":0' <<< "$stats"; then
  echo "Native Java parsing regression: $stats" >&2
  exit 1
fi
members=$("$binary" members "$workspace" Counter --json)
for name in value items DEFAULT current; do
  if ! grep -q "\"name\":\"$name\"" <<< "$members"; then
    echo "Native Java member missing: $name" >&2
    exit 1
  fi
done
echo 'Native Java parsing and member extraction passed.'
