#!/bin/bash
versionStr="extVersionCode ="
multisrcVersionStr="overrideVersionCode ="
bumpedFiles=""


# cut -d "=" -f 2 -> string.split("=")[1]
# "extVersionCode = 6" -> ["extVersionCode ", " 6"] -> " 6" -> "6"
getValue() { cut -d "=" -f 2 | cut -d " " -f 2;}
getVersion() {
    if [[ $1 =~ ^multisrc/ ]]; then
        # We are going to be piped, so no file specified, just read from stdin.
        grep -Po "$multisrcVersionStr \d+"  | getValue
    else
        grep "$versionStr" "$1" | getValue
    fi
}

# expected input: multisrc/overrides/<theme>/<override>/....
# if override is default, then it will bump all overrides.
bumpMultisrcVersion() {
    local overridePath=$1
    # Prevents bumping extensions multiple times.
    # Ex: When a theme uses a extractor per default, but one extension(override)
    # also uses another, so if both libs are modifyed, such extension will be
    # bumped only once instead of two times.
    if [[ $bumpedFiles =~( |^)$overridePath( |$) ]]; then
        return 0
    fi

    bumpedFiles+="$overridePath "

    # Bump all extensions from a multisrc that implements a lib by default
    if [[ $overridePath =~ .*/default/.* ]]; then
        local themeBase=$(echo $overridePath | cut -d/ -f-3)
        for file in $(ls $themeBase | grep -v default); do
            bumpMultisrcVersion $themeBase/$file/
        done
    else
        local theme=$(echo $overridePath | cut -d/ -f3)
        local themePath="multisrc/src/main/java/eu/kanade/tachiyomi/multisrc/$theme"
        local sourceName=$(echo $overridePath | cut -d/ -f4)
        local generator=$(echo $themePath/*Generator.kt)
        bumpedFiles+="$generator " # Needed to commit the changes

        local sourceLine=$(grep "Lang(" $generator | grep -i $sourceName)
        local oldVersion=$(echo $sourceLine | getVersion $generator)
        local newVersionStr="$multisrcVersionStr $((oldVersion + 1))"

        if [[ $sourceLine =~ .*$multisrcVersionStr.* ]]; then
            # if the override already have a "overrideVersionCode" param at 
            # the generator, then just bump it
            local newSourceLine=${sourceLine/$multisrcVersionStr $oldVersion/$newVersionStr}
        else
            # else, add overrideVersionCode param to its line on the generator
            local newSourceLine=${sourceLine/)/, $newVersionStr)}
        fi

        echo -e "\nmultisrc $sourceName($theme): $oldVersion -> $((oldVersion + 1))\n"
        sed -i "s@$sourceLine@$newSourceLine@g" $generator
    fi
}

bumpVersion() {
    local file=$1
    local oldVersion=$(getVersion $file)
    local newVersion=$((oldVersion + 1))

    echo -e "\n$file: $oldVersion -> $newVersion\n"
    sed -i "s/$versionStr $oldVersion/$versionStr $newVersion/" $file
}

findAndBump() {
    for lib in $@; do
        for file in $(grep -l -R ":lib:$lib" --include "build.gradle" --include "additional.gradle"); do
            # prevent bumping the same extension multiple times
            if [[ ! $bumpedFiles =~ ( |^)$file( |$) ]]; then
                if [[ $file =~ ^multisrc ]]; then
                    bumpMultisrcVersion ${file/additional.gradle/}
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
