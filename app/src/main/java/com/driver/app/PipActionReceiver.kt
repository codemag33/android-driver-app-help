package com.driver.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver, который обрабатывает нажатие на RemoteAction-кнопку
 * «Завершить заказ» внутри PiP-окна.
 *
 * Схема работы:
 *  1. Пользователь нажимает кнопку в PiP-окне.
 *  2. Система рассылает Broadcast с action = ACTION_FINISH_ORDER.
 *  3. Receiver получает Intent, логирует событие и запускает MainActivity
 *     с флагом EXTRA_FINISH_ORDER = true + FLAG_ACTIVITY_SINGLE_TOP, чтобы
 *     не создавать новый экземпляр Activity.
 *  4. MainActivity перехватывает Intent в onNewIntent() и обрабатывает завершение.
 */
class PipActionReceiver : BroadcastReceiver() {

    companion object {
        /** Action, который регистрируется в AndroidManifest и PiP-параметрах */
        const val ACTION_FINISH_ORDER = "com.driver.app.ACTION_FINISH_ORDER"

        /** Ключ флага в Intent для MainActivity */
        const val EXTRA_FINISH_ORDER = "extra_finish_order"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FINISH_ORDER) return

        Log.d("PipActionReceiver", "Получен сигнал «Завершить заказ»")

        // Разворачиваем MainActivity и передаём флаг завершения заказа.
        // FLAG_ACTIVITY_SINGLE_TOP + FLAG_ACTIVITY_REORDER_TO_FRONT гарантируют,
        // что Activity не пересоздаётся, а вызывается onNewIntent().
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_FINISH_ORDER
            putExtra(EXTRA_FINISH_ORDER, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        context.startActivity(activityIntent)
    }
}
