#!/bin/sh
# Mechanical checks of Ballarch author rules R1 and R2 for one C decider translation unit.
#
# They look for the usual ways a decider reaches outside its arguments: a call into
# other code, data that outlives a step, registers, special instructions, clocks, and
# addresses made from integers. They are a lint for mistakes and shortcuts, not a
# sandbox: code written to get past them can (see "Not detected" below).
#
# Object file (compiled with -fno-pic, so that const tables of pointers are read-only data, and
# without stack-protector instrumentation, whose symbols are not the decider's):
#   R2  no undefined symbol except compiler runtime helpers, which compilers emit for
#       plain C on targets without the instruction: memcpy, memset, memmove (struct
#       copies), the ARM EABI helpers __aeabi_*, the libgcc/compiler-rt arithmetic
#       helpers by the exact shape of their names (__udivsi3, __udivmoddi4, __adddf3,
#       __fixdfsi, ...; not every name that starts like one), Thumb-1 switch helpers;
#       and functions defined by a pure dependency named with --uses (which must pass
#       these checks itself).
#   R1  no non-empty allocated section that is not read-only, whatever its name (.data,
#       .bss, thread-local, small data, .data.rel.ro) and no common symbol. The message
#       names the sections and the symbols in them.
# Preprocessed source: the decider's own lines, that is, lines from every file that is not
#   under one of the compiler's own include directories (as `cc -v` lists them; a header
#   that calls itself a system header with #pragma still counts as the decider's own):
#   R2  the only system headers it includes are the C11 freestanding ones (stddef.h,
#       stdint.h, ...: types and macros, no functions); it does not include the runtime
#       header ballarch_runtime.h; it has no #line directive or line marker that names
#       another file (which would relabel its lines); no volatile and no inline
#       assembly; no compiler builtin except pure ones (offsetof, expect, unreachable,
#       bit counts, byte swaps, overflow checks, va_*, constant_p, choose_expr,
#       types_compatible_p) and no __atomic/__sync/__c11_atomic builtin; no cast to a
#       pointer type (`NULL` expands to one: use 0); no typedef of a pointer type, no typeof or __auto_type (a cast through them
#       would not show); no union (an integer can become a pointer through one); no
#       uintptr_t/intptr_t; an integer converted to a pointer without a cast is a compile
#       error here (-Werror=int-conversion).
# Clang AST (when clang is installed; CLANG= turns it off, CLANG="clang --target=..." sets
#   the target), nodes in the decider's own files:
#   R2  no cast to a pointer type, however the type is spelled (a typedef after a struct
#       body, a macro); no conversion of an integer to a pointer; no call through a
#       function pointer (only direct calls of named functions, which the object check
#       then sees).
#
# Not detected (left to review, Core §8): pointer arithmetic from an argument into other
# memory without a cast; memcpy of an ordinary integer into a pointer object, with memcpy
# declared by hand; a struct the decider declares to mirror the runtime's layout; reads
# of uninitialised memory; non-termination; deliberate evasion, such as a line marker
# hidden behind a comment or a line splice; and, without clang, a cast through a pointer
# typedef declared after a struct body.
#
# Usage (from reference/c): sh tools/check_purity.sh [--uses pure.c]... decider.c [target flags]
#   CC may carry flags, e.g. CC="clang --target=thumbv7em-none-eabi"; NM and OBJDUMP may name
#   the matching binutils. Target flags may also follow the file name (not -I options).
set -eu
uses=""
while [ "${1:-}" = "--uses" ]; do
    uses="$uses $2"
    shift 2
done
src=$1
shift
cc=${CC:-cc}
nm=${NM:-nm}
objdump=${OBJDUMP:-objdump}
clang=${CLANG-clang}
dir=$(dirname "$src")
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ballarch-purity.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
status=0

# Pure dependencies: each must pass on its own; the functions it defines may be called.
: > "$tmp/allowed"
for dep in $uses; do
    if ! sh "$0" "$dep" "$@" > "$tmp/dep.out" 2>&1; then
        echo "R2 violated in $src: uses $dep, which fails the purity checks:"
        sed 's/^/  /' "$tmp/dep.out"
        status=1
    fi
    # $cc is unquoted on purpose: CC may carry flags.
    $cc -std=c11 -O2 -fno-pic -fno-stack-protector -c -Iinclude -I"$(dirname "$dep")" "$@" -o "$tmp/dep.o" "$dep"
    "$nm" -g --defined-only "$tmp/dep.o" | awk 'NF == 3 {print $3}' >> "$tmp/allowed"
done

if ! $cc -std=c11 -O2 -fno-pic -fno-stack-protector -Werror=int-conversion -c -Iinclude -I"$dir" "$@" -o "$tmp/obj.o" "$src" 2> "$tmp/cc.err"; then
    echo "R2 check failed for $src: it does not compile with integer-to-pointer conversions as errors:"
    sed 's/^/  /' "$tmp/cc.err"
    exit 1
fi

# R2, object: undefined symbols. Helper names: libgcc's integer (__divsi3 ... __udivmoddi4,
# __ashldi3, __clzsi2, __bswapsi2) and soft-float (__addsf3, __eqdf2, __fixdfsi, __floatsidf,
# __extendsfdf2) helpers, and the ARM EABI ones.
helpers='memcpy|memset|memmove|_GLOBAL_OFFSET_TABLE_|__aeabi_(u?l?idiv(mod)?|uldivmod|ldivmod|lmul|llsl|llsr|lasr|lcmp|ulcmp|mem(cpy|move|set|clr)[48]?|[fd](add|sub|rsub|mul|div|neg|cmp(eq|lt|le|ge|gt|un)?|cmpun|rcmple|2[dfiz]|2u?i?l?z)|[ui]?[il]?2[fd]|ul?2[fd]|[fd]2u?l?i?z|f2d|d2f|cfcmp[a-z]*|cdcmp[a-z]*|cdrcmple|cfrcmple)|__gnu_thumb1_case_[a-z0-9]+|__(ashl|ashr|lshr|mul|div|mod|udiv|umod|udivmod|divmod|neg|cmp|ucmp|clz|ctz|ffs|popcount|parity|bswap|clrsb)[qhsdt]i[234]|__(add|sub|mul|div|neg|eq|ne|lt|le|gt|ge|unord|cmp)[hsdtx]f[23]|__fix(uns)?[hsdtx]f[sdt]i|__float(un)?[sdt]i[hsdtx]f|__(extend|trunc)[hsdtx]f[hsdtx]f2|__powi[sd]f2|__(mul|div)[sd]c3'
undefined=$("$nm" -u "$tmp/obj.o" | awk '{print $NF}' | grep -vxE "$helpers" | grep -vxF -f "$tmp/allowed" | tr '\n' ' ' || true)
if [ -n "$undefined" ]; then
    echo "R2 violated in $src: references external symbols: $undefined"
    status=1
fi

# R1, object: writable sections, by their flags (not their names), and the symbols in them.
sections=$("$objdump" -h "$tmp/obj.o" | awk '/^ *[0-9]+ / { n = $2; s = $3; getline; if ($0 ~ /ALLOC/ && $0 !~ /READONLY/ && s !~ /^0+$/) print n }' | tr '\n' ' ' || true)
# Symbols in those sections, and common symbols (which have no section in an object file).
symbols=$("$objdump" -t "$tmp/obj.o" | awk -v secs=" $sections " '{ sec = ""; for (i = 2; i < NF; i++) if ($i ~ /^[.*]/) { sec = $i; break }
    if (sec != "" && $NF !~ /^\./ && (sec == "*COM*" || index(secs, " " sec " "))) print $NF }' | sort -u | tr '\n' ' ' || true)
if [ -n "$sections$symbols" ]; then
    echo "R1 violated in $src: defines writable data: ${sections}${symbols}"
    status=1
fi

# Which files are the compiler's own (system) headers: those under the directories its -v
# search list names for these target flags, without any -I, -iquote, -isystem or -idirafter.
search_dirs() {
    skip=0
    for a do
        shift
        if [ "$skip" = 1 ]; then skip=0; continue; fi
        case $a in
            -I | -iquote | -isystem | -idirafter) skip=1; continue ;;
            -I?* | -iquote?* | -isystem?* | -idirafter?*) continue ;;
        esac
        set -- "$@" "$a"
    done
    "$@" -std=c11 -E -v -x c /dev/null -o /dev/null 2>&1 |
        awk '/search starts here:/ {on = 1; next} /End of search list/ {on = 0} on && /^ / {sub(/^ +/, ""); sub(/ \(framework directory\)$/, ""); print}'
}
# Reads file names, one per line; writes "S|U|P<TAB>name relative to its system directory<TAB>name",
# with the system directories listed in file $1 (P: <built-in> and the like).
classify() {
    while IFS= read -r f; do
        case $f in
            "<"* | "") printf 'P\t\t%s\n' "$f"; continue ;; # <built-in>, <command-line>
        esac
        if d=$(cd "$(dirname -- "$f")" 2> /dev/null && pwd -P); then real=$d/$(basename -- "$f"); else real=$f; fi
        class=U
        rel=""
        while IFS= read -r d; do
            case $real in "$d"/*) class=S; rel=${real#"$d"/}; break ;; esac
        done < "$1"
        printf '%s\t%s\t%s\n' "$class" "$rel" "$f"
    done
}
real_dirs() { while IFS= read -r d; do (cd "$d" 2> /dev/null && pwd -P) || true; done; }
# shellcheck disable=SC2086 # $cc may carry flags
search_dirs $cc "$@" | real_dirs > "$tmp/sysdirs"

# R2, preprocessed source.
$cc -std=c11 -E -Iinclude -I"$dir" "$@" "$src" > "$tmp/pp.i"
sed -nE 's/^# [0-9]+ "(([^"\\]|\\.)*)".*/\1/p' "$tmp/pp.i" | sort -u | classify "$tmp/sysdirs" > "$tmp/classes"
# Prints the decider's own lines; "#file f" when they come from file f; "#system h" for a system
# header h that the decider's own code includes; "#rename f -> g" for a line directive in its own
# code that names another file.
awk -v classes="$tmp/classes" '
    BEGIN { FS = "\n"; while ((getline l < classes) > 0) { split(l, p, "\t"); cls[p[3]] = p[1]; rel[p[3]] = p[2] } }
    /^# [0-9]+ "/ {
        f = $0; sub(/^# [0-9]+ "/, "", f); flags = f; sub(/"[^"]*$/, "", f); sub(/^.*"/, "", flags)
        enter = flags ~ /(^| )1( |$)/; leave = flags ~ /(^| )2( |$)/
        own = cur != "" && cls[cur] == "U"
        if (own && enter && cls[f] == "S") print "#system " rel[f]
        if (own && !enter && !leave && f != cur && cls[f] != "P") print "#rename " cur " -> " f
        cur = f
        if (cls[cur] == "U") print "#file " f
        next
    }
    /^#/ { next }
    cls[cur] == "U" { print }' "$tmp/pp.i" |
    sed -E 's/"([^"\\]|\\.)*"/""/g; s/'"'"'([^'"'"'\\]|\\.)*'"'"'/0/g' > "$tmp/user.i"
if grep -qE '^#file .*ballarch_runtime\.h$' "$tmp/user.i"; then
    echo "R2 violated in $src: includes the runtime header ballarch_runtime.h"
    status=1
fi
hosted=$(sed -n 's/^#system //p' "$tmp/user.i" | grep -vxE 'float\.h|iso646\.h|limits\.h|stdalign\.h|stdarg\.h|stdbool\.h|stddef\.h|stdint\.h|stdnoreturn\.h' | sort -u | tr '\n' ' ' || true)
if [ -n "$hosted" ]; then
    echo "R2 violated in $src: includes system headers that are not C11 freestanding headers (their inline functions are not checked): $hosted"
    status=1
fi
# Line markers written in the decider's own files (after joining spliced lines); #line is seen above.
markers=$(sed -n 's/^#file //p' "$tmp/user.i" | sort -u | while IFS= read -r f; do
    [ -f "$f" ] && sed -e ':a' -e '/\\$/N; s/\\\n//; ta' "$f" | grep -nE '^[[:space:]]*(#|%:|\?\?=)[[:space:]]*[0-9]' | sed "s|^|$f:|" || true
done)
renames=$(sed -n 's/^#rename //p' "$tmp/user.i" | sort -u)
if [ -n "$markers$renames" ]; then
    echo "R2 violated in $src: line directives that relabel the decider's own lines as another file's:"
    printf '%s\n%s\n' "$renames" "$markers" | sed '/^$/d; s/^/  /'
    status=1
fi
grep -v '^#' "$tmp/user.i" > "$tmp/code.i" || true
tr -s '\n\t ' '   ' < "$tmp/code.i" > "$tmp/joined.i"
tokens() { grep -oE "(^|[^A-Za-z0-9_])($1)([^A-Za-z0-9_]|\$)" "$tmp/code.i" | sed -E 's/[^A-Za-z0-9_]//g' | sort -u | tr '\n' ' ' || true; }
volatiles=$(tokens 'volatile|__volatile__|__volatile')
if [ -n "$volatiles" ]; then
    echo "R2 violated in $src: volatile access (memory-mapped I/O): $volatiles"
    status=1
fi
assembly=$(tokens 'asm|__asm|__asm__')
if [ -n "$assembly" ]; then
    echo "R2 violated in $src: inline assembly: $assembly"
    status=1
fi
builtins=$(grep -oE '(__builtin|__atomic|__sync|__c11_atomic)_[A-Za-z0-9_]+' "$tmp/code.i" |
    grep -vxE '__builtin_(offsetof|expect|unreachable|constant_p|choose_expr|types_compatible_p|(clz|ctz|popcount|parity|ffs|clrsb)(l|ll)?|bswap(16|32|64)|(add|sub|mul)_overflow|[su](add|sub|mul)l?l?_overflow|va_(list|start|end|arg|copy))' |
    sort -u | tr '\n' ' ' || true)
if [ -n "$builtins" ]; then
    echo "R2 violated in $src: compiler builtins outside the pure list (clocks, special registers, memory): $builtins"
    status=1
fi
# Casts to a pointer type: "( type * )" followed by an operand. sizeof and alignof take type names, not casts.
sed -E 's/(sizeof|_Alignof|__alignof__|alignof)[[:space:]]*\(/\1 [/g' "$tmp/joined.i" > "$tmp/nosizeof.i"
ws='[[:space:]]*'
casts=$(grep -oE "\(${ws}((const|volatile|restrict|struct|union|enum)[[:space:]]+)*[A-Za-z_][A-Za-z0-9_]*([[:space:]]+[A-Za-z_][A-Za-z0-9_]*)*${ws}\*[*[:space:]]*((const|volatile|restrict)${ws})*\)${ws}[A-Za-z0-9_(&*~!+\"'-][^;]{0,40}|\(${ws}\*${ws}\)[^;]{0,40}" "$tmp/nosizeof.i" |
    cut -c1-80 || true)
if [ -n "$casts" ]; then
    echo "R2 violated in $src: casts to a pointer type (an address made from an integer, or a pointer into other memory):"
    echo "$casts" | sed 's/^/  /'
    status=1
fi
ptypes=$( { grep -oE '(^|[^A-Za-z0-9_])typedef[^;{}]*\*[^;{}]*;' "$tmp/joined.i" | sed -E 's/^[^t]*//'; tokens 'typeof|__typeof__|__typeof|__auto_type' | tr ' ' '\n'; } | sed '/^$/d' | cut -c1-80 | sort -u || true)
if [ -n "$ptypes" ]; then
    echo "R2 violated in $src: pointer typedefs or typeof (a cast through them would not show here):"
    echo "$ptypes" | sed 's/^/  /'
    status=1
fi
unions=$(tokens 'union')
if [ -n "$unions" ]; then
    echo "R2 violated in $src: union (an integer can become a pointer through one)"
    status=1
fi
conversions=$(tokens 'u?intptr_t')
if [ -n "$conversions" ]; then
    echo "R2 violated in $src: integer/pointer conversion type: $conversions"
    status=1
fi

# R2, clang AST: casts and calls, whatever their spelling. Each node belongs to the file of the
# first location printed for it (the dump prints a file name only when it changes).
ast_check=""
if [ -n "$clang" ] && command -v "${clang%% *}" > /dev/null 2>&1; then
    # shellcheck disable=SC2086 # $clang may carry flags
    if ! $clang -std=c11 -fsyntax-only -Xclang -ast-dump -fno-color-diagnostics -Iinclude -I"$dir" "$@" "$src" > "$tmp/ast.txt" 2> "$tmp/ast.err"; then
        echo "R2 check failed for $src: clang ($clang) cannot parse it for the AST check (set CLANG to a clang for the target, or CLANG= to skip):"
        sed 's/^/  /' "$tmp/ast.err"
        exit 1
    fi
    ast='
    function locate(s,    tok, n, p, first) {
        gsub(/"([^"\\]|\\.)*"/, "\"\"", s); gsub(/\047[^\047]*\047/, "\047\047", s)
        gsub(/<scratch space>/, "<scratch>", s); gsub(/<built-in>/, "<builtin>", s)
        first = 0
        while (match(s, /[^ <>,=]+:[0-9]+(:[0-9]+)?/)) {
            tok = substr(s, RSTART, RLENGTH); s = substr(s, RSTART + RLENGTH)
            n = split(tok, p, ":")
            if (p[1] == "line" && n == 3) line = p[2]
            else if (n == 3 && p[1] != "col") {
                file = p[1]; line = p[2]
                if (mode == "names" && !(file in seen)) { seen[file] = 1; print file }
            } else if (p[1] != "col") continue
            if (!first) { first = 1; nodefile = file; nodeline = line }
        }
        if (!first) { nodefile = file; nodeline = line }
    }
    function pointer(t) { return t ~ /\*( *(const|volatile|restrict|__restrict))* *$/ || t ~ /\(\*/ }
    BEGIN { if (mode == "check") while ((getline l < classes) > 0) { split(l, p, "\t"); cls[p[3]] = p[1] } }
    {
        raw = $0; locate(raw)
        if (mode == "names") next
        kind = raw; sub(/^[| `-]*/, "", kind); sub(/ .*/, "", kind)
        where = nodefile ":" nodeline
        if (pending == 2) {
            if (kind != "DeclRefExpr" || raw !~ / Function 0x/) print "call\t" callat
            pending = 0
        } else if (pending == 1) {
            if (kind == "ImplicitCastExpr" && raw ~ /<(FunctionToPointerDecay|BuiltinFnToFnPtr)>/) pending = 2
            else { print "call\t" callat; pending = 0 }
        }
        if (nodefile == "" || (nodefile in cls && cls[nodefile] != "U")) next
        if (kind == "CallExpr") { pending = 1; callat = where }
        if (kind == "CStyleCastExpr") {
            t = raw; sub(/ <[A-Za-z]+>( part_of_explicit_cast)?$/, "", t); sub(/ (lvalue|xvalue|prvalue)$/, "", t)
            if (match(t, /\047[^\047]*\047$/)) { t = substr(t, RSTART + 1, RLENGTH - 2); if (pointer(t)) print "cast\t" where " " t }
        }
        if (raw ~ /<IntegralToPointer>/) print "cast\t" where " integer to pointer"
    }'
    # shellcheck disable=SC2086 # $clang may carry flags
    search_dirs $clang "$@" | real_dirs > "$tmp/ast.sysdirs"
    awk -v mode=names "$ast" "$tmp/ast.txt" | classify "$tmp/ast.sysdirs" > "$tmp/ast.classes"
    awk -v mode=check -v classes="$tmp/ast.classes" "$ast" "$tmp/ast.txt" | sort -u > "$tmp/ast.out"
    if grep -q '^cast' "$tmp/ast.out"; then
        echo "R2 violated in $src (clang AST): casts to a pointer type: $(sed -n 's/^cast\t//p' "$tmp/ast.out" | tr '\n' ';' | sed 's/;$//; s/;/; /g')"
        status=1
    fi
    if grep -q '^call' "$tmp/ast.out"; then
        echo "R2 violated in $src (clang AST): calls through a function pointer: $(sed -n 's/^call\t//p' "$tmp/ast.out" | tr '\n' ';' | sed 's/;$//; s/;/; /g')"
        status=1
    fi
    ast_check=", clang AST"
else
    echo "note: the clang AST checks did not run for $src (CLANG is empty, or not installed)"
fi

[ "$status" -eq 0 ] && echo "R1/R2 checks passed for $src (object file, preprocessed source$ast_check)"
exit "$status"
