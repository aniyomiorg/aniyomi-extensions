#!/bin/bash
versionStr="extVersionCode ="

getVersion() {
    # cut -d "=" -f 2 -> string.split("=")[1]
    # "extVersionCode = 6" -> ["extVersionCode ", " 6"] -> " 6" -> "6"
    grep "$versionStr" "$1" | cut -d "=" -f 2 | cut -d " " -f 2
}

bumpVersion() {
    local file=$1
    local old_version=$(getVersion $file)
    local new_version=$((old_version + 1))

    echo -e "\n$file: $old_version -> $new_version\n"
    sed -i "s/$versionStr $old_version/$versionStr $new_version/" $file
}

findAndBump() {
    local bumpedFiles=""
    for lib in $@; do
        for file in $(grep -l -R ":lib-$lib" --include "build.gradle"); do
            # prevent bumping the same extension multiple times
            if [[ ! $bumpedFiles =~ ( |^)$file( |$) ]]; then
                bumpedFiles+="$file "
                bumpVersion $file
            fi
        done
    done
    commitChanges $bumpedFiles
}

commitChanges() {
    # this will NOT trigger another workflow, because it will use $GITHUB_TOKEN.
    # so the build-action will run fine with the bumped-up extensions
    if [[ -n "$@" ]]; then
        git config --global user.email "github-actions[bot]@users.noreply.github.com"
        git config --global user.name "github-actions[bot]"
        git add $@
        git commit -m "Mass-bump on extensions"
        git push
    fi
}

# lib/cryptoaes/build.gradle.kts -> lib/cryptoaes -> cryptoaes
modified=$(echo $@ | tr " " "\n" | grep -Eo "^lib/\w+" | sort | uniq | cut -c 5-)
if [[ -n "$modified" ]]; then
    findAndBump $modified
fi
