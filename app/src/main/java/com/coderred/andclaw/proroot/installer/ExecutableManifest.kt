package com.coderred.andclaw.proroot.installer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

class ExecutableManifest(
    private val context: Context,
    private val manifestAssetName: String = "executable-manifest.json",
) {
    companion object {
        private const val TAG = "ExecutableManifest"
    }

    private val rulesByAsset: Map<String, List<String>> by lazy { loadRules() }

    fun apply(assetName: String, rootDir: File): Int {
        val rules = rulesByAsset[assetName].orEmpty()
        if (rules.isEmpty()) return 0

        var changed = 0
        rules.forEach { rule ->
            if (rule.contains("*")) {
                rootDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                        if (globMatch(rule, relative) && file.setExecutable(true, false)) {
                            changed++
                        }
                    }
            } else {
                val target = File(rootDir, rule)
                if (target.isFile && target.setExecutable(true, false)) {
                    changed++
                }
            }
        }
        return changed
    }

    private fun loadRules(): Map<String, List<String>> {
        val jsonText = try {
            context.assets.open(manifestAssetName).bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            Log.w(TAG, "Asset not found: $manifestAssetName, skipping executable rules")
            return emptyMap()
        }
        val root = JSONObject(jsonText)
        val assetsObj = root.optJSONObject("assets") ?: JSONObject()
        val out = linkedMapOf<String, List<String>>()
        val keys = assetsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = assetsObj.optJSONArray(key).toStringList()
        }
        return out
    }

    private fun globMatch(pattern: String, path: String): Boolean {
        val regex = buildString {
            append("^")
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                if (c == '*') {
                    val isDouble = i + 1 < pattern.length && pattern[i + 1] == '*'
                    append(if (isDouble) ".*" else "[^/]*")
                    if (isDouble) i++
                } else {
                    if ("\\.[]{}()+-^$|".contains(c)) append("\\")
                    append(c)
                }
                i++
            }
            append("$")
        }
        return Regex(regex).matches(path)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotEmpty()) list += value
        }
        return list
    }
}
