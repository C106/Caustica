$ErrorActionPreference = "Stop"

$upscalerRoot = $PSScriptRoot

Push-Location $upscalerRoot
try {
	$env:JAVA_TOOL_OPTIONS='-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC -Dupscaler.rt.fg=false -Dupscaler.rt.reflex=false -Dupscaler.rt.frameStats=false -Dupscaler.rt.lodWorld=true -Dupscaler.rt.lodDebug=true -Dupscaler.rt.poolStats=false'
	.\gradlew.bat --stop
	.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
} finally {
	Pop-Location
}
