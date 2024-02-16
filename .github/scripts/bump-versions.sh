#!/bin/bash
versionStr="VersionCode ="
bumpedFiles=""

# cut -d "=" -f 2 -> string.split("=")[1]
# "extVersionCode = 6" -> ["extVersionCode ", " 6"] -> " 6" -> "6"
getValue() { cut -d "=" -f 2 | cut -d " " -f 2;}
getVersion() {
    grep "$versionStr" "$1" | getValue
}

bumpVersion() {
    local file=$1
    local oldVersion=$(getVersion $file)
    local newVersion=$((oldVersion + 1))

    echo -e "\n$file: $oldVersion -> $newVersion\n"
    sed -i "s/$versionStr $oldVersion/$versionStr $newVersion/" $file
}

bumpLibMultisrcVersion() {
    local themeName=$(echo $1 | grep -Eo "lib-multisrc/\w+" | cut -c 14-)
    for file in $(grep -l -R "themePkg = '$themeName'" --include build.gradle src/); do
        # prevent bumping the same extension multiple times
        if [[ ! $bumpedFiles =~ ( |^)$file( |$) ]]; then
            bumpedFiles+="$file "
            bumpVersion $file
        fi
    done
}

findAndBump() {
    for lib in $@; do
        for file in $(grep -l -R ":lib:$lib" --include "build.gradle" --include "build.gradle.kts" src/ lib-multisrc/); do
            # prevent bumping the same extension multiple times
            if [[ ! $bumpedFiles =~ ( |^)$file( |$) ]]; then
                if [[ $file =~ ^lib-multisrc ]]; then
                    bumpLibMultisrcVersion ${file/build.gradle.kts/}
                else
                    bumpedFiles+="$file "
                    bumpVersion $file
                fi
            fi
        done
    done

    commitChanges $bumpedFiles
}

commitChanges() {
    if [[ -n "$@" ]]; then
        git config --global user.email "aniyomi-bot@aniyomi.org"
        git config --global user.name "aniyomi-bot[bot]"
        git add $@
        git commit -S -m "[skip ci] chore: Mass-bump on extensions"
        git push
    fi
}

# lib/cryptoaes/build.gradle.kts -> lib/cryptoaes -> cryptoaes
modified=$(echo $@ | tr " " "\n" | grep -Eo "^lib/\w+" | sort | uniq | cut -c 5-)
if [[ -n "$modified" ]]; then
    findAndBump $modified
fi
