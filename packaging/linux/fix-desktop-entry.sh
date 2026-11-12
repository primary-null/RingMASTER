#!/bin/sh
# Rewrites RingMASTER .desktop entries: Name, Categories with a trailing semicolon,
# and StartupWMClass. jpackage names the entry from the package name, so release
# packages are patched here. A symbolic link is not followed.
set -eu

WM_CLASS=local-ringleader-desktop-MainKt

fix_one() {
  file=$1
  if [ -L "$file" ]; then
    echo "refusing to edit a symlink: $file" >&2
    exit 1
  fi
  tmp=$(mktemp)
  awk '
    BEGIN { name=0; cat=0; wm=0 }
    /^Name=/ { print "Name=RingMASTER"; name=1; next }
    /^Categories=/ {
      line = $0
      if (substr(line, length(line), 1) != ";") line = line ";"
      print line
      cat = 1
      next
    }
    /^StartupWMClass=/ { print "StartupWMClass=local-ringleader-desktop-MainKt"; wm=1; next }
    { print }
    END {
      if (!name) print "Name=RingMASTER"
      if (!cat) print "Categories=System;"
      if (!wm) print "StartupWMClass=local-ringleader-desktop-MainKt"
    }
  ' "$file" > "$tmp"
  mv "$tmp" "$file"
}

require_one() {
  file=$1
  grep -qx 'Name=RingMASTER' "$file"
  grep -Eq '^Categories=.*;$' "$file"
  grep -qx "StartupWMClass=${WM_CLASS}" "$file"
}

desktop_list() {
  dir=$1
  list=$(mktemp)
  find "$dir" -type f -name '*.desktop' > "$list"
  if [ ! -s "$list" ]; then
    echo "no desktop file under $dir" >&2
    rm -f "$list"
    exit 1
  fi
  printf '%s' "$list"
}

fix_tree() {
  dir=$1
  list=$(desktop_list "$dir")
  while IFS= read -r desktop; do
    sh "$0" --file "$desktop" || exit 1
    sh "$0" --require "$desktop" || exit 1
  done < "$list"
  rm -f "$list"
}

require_tree() {
  dir=$1
  list=$(desktop_list "$dir")
  while IFS= read -r desktop; do
    sh "$0" --require "$desktop" || exit 1
  done < "$list"
  rm -f "$list"
}

case ${1:-} in
  --file)
    fix_one "$2"
    ;;
  --require)
    require_one "$2"
    ;;
  --dir)
    fix_tree "$2"
    ;;
  --deb)
    pkg=$2
    work=$(mktemp -d)
    dpkg-deb -R "$pkg" "$work"
    fix_tree "$work"
    dpkg-deb -b "$work" "$pkg"
    rm -rf "$work"
    ;;
  --rpm)
    pkg=$2
    self=$(CDPATH= cd "$(dirname "$0")" && pwd)/$(basename "$0")
    stamp=$(mktemp)
    touch "$stamp"
    # rpmrebuild runs this command with the package tree as its working directory.
    # The rebuilt package is written under ~/rpmbuild/RPMS, then copied back.
    change="find . -type f -name '*.desktop' -exec sh '$self' --file {} \\; -exec sh '$self' --require {} \\; ; find . -type f -name '*.desktop' | grep -q ."
    rpmrebuild --batch --package --notest-install --change-files "$change" "$pkg"
    new=$(find "$HOME/rpmbuild/RPMS" -type f -name '*.rpm' -newer "$stamp" | head -n 1)
    if [ -z "$new" ]; then
      echo "rpmrebuild did not leave a package under \$HOME/rpmbuild/RPMS" >&2
      find "$HOME/rpmbuild" -type f -name '*.rpm' >&2 || true
      exit 1
    fi
    cp -f "$new" "$pkg"
    verify=$(mktemp -d)
    (cd "$verify" && rpm2cpio "$pkg" | cpio -id >/dev/null)
    require_tree "$verify"
    rm -rf "$verify" "$stamp"
    ;;
  --self-check)
    dir=$(mktemp -d)
    printf '%s\n' '[Desktop Entry]' 'Name=ringmaster' 'Categories=System' 'Type=Application' > "$dir/a.desktop"
    printf '%s\n' '[Desktop Entry]' 'Type=Application' > "$dir/b.desktop"
    sh "$0" --dir "$dir"
    rm -rf "$dir"
    ;;
  *)
    echo "Use --file, --require, --dir, --deb, --rpm or --self-check" >&2
    exit 1
    ;;
esac
