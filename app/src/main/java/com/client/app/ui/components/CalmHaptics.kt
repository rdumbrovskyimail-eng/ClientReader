package com.client.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Тактильные отклики «вполголоса».
 *
 * Только на главных действиях (старт/стоп разговора, пауза/продолжение чтения), всегда слабые:
 * сильная вибрация воспринимается как тревожный сигнал. Переключатели и чипы отвечают визуально.
 */
object CalmHaptics {

    /** Едва ощутимое касание — «принято». */
    fun soft(context: Context) = play(context, primitiveLowTick = true, scale = 0.55f)

    /** Чуть заметнее — для главной кнопки разговора. */
    fun confirm(context: Context) = play(context, primitiveLowTick = false, scale = 0.45f)

    private fun play(context: Context, primitiveLowTick: Boolean, scale: Float) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() != true) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val primitive = if (primitiveLowTick) {
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                } else {
                    VibrationEffect.Composition.PRIMITIVE_TICK
                }
                if (vibrator.areAllPrimitivesSupported(primitive)) {
                    vibrator.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(primitive, scale, 0)
                            .compose()
                    )
                    return
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
            // На Android 9 без «тика» не вибрируем вовсе: грубый импульс хуже, чем тишина
        }
    }
}