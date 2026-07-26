package com.alad1nks.jaiqal.domain

data class Calibration(val dryRaw:Int,val wetRaw:Int) { fun percent(raw:Int):Int = (((raw-dryRaw).toDouble()/(wetRaw-dryRaw))*100).coerceIn(0.0,100.0).toInt() }
sealed interface CalibrationResult { data class Valid(val value:Calibration):CalibrationResult; data object InsufficientSamples:CalibrationResult; data object Unstable:CalibrationResult; data object TooClose:CalibrationResult }
fun calibration(dry:List<Int>,wet:List<Int>,minimumDifference:Int=100):CalibrationResult {
    if(dry.size<3 || wet.size<3) return CalibrationResult.InsufficientSamples
    if((dry.max()-dry.min())>minimumDifference || (wet.max()-wet.min())>minimumDifference) return CalibrationResult.Unstable
    val d=dry.sorted()[dry.size/2]; val w=wet.sorted()[wet.size/2]
    return if(kotlin.math.abs(d-w)<minimumDifference) CalibrationResult.TooClose else CalibrationResult.Valid(Calibration(d,w))
}
