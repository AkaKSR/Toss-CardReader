package com.toss.cardapp.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import com.toss.cardapp.core.Decision
import com.toss.cardapp.core.RuleAction
import com.toss.cardapp.core.Screen

object RuleRunner {
    fun run(activity: Activity, decision: Decision, kiosk: Boolean) {
        val rule = decision.rule ?: return
        when (val a = rule.action) {
            is RuleAction.OpenScreen -> when (a.screen) {
                Screen.GREETING -> activity.startActivity(
                    Intent(activity, GreetingActivity::class.java)
                        .putExtra(GreetingActivity.EXTRA_NAME, decision.member?.name)
                        .putExtra(GreetingActivity.EXTRA_VERDICT, decision.verdict.name)
                )
                Screen.LOG, Screen.MEMBERS -> if (kiosk) {
                    toast(activity, "키오스크 모드에서는 관리 화면을 열지 않아요")
                } else {
                    activity.startActivity(Intent(activity, if (a.screen == Screen.LOG) LogActivity::class.java else MembersActivity::class.java))
                }
            }
            is RuleAction.OpenApp -> {
                if (kiosk) {
                    toast(activity, "키오스크 모드에서는 다른 앱을 열 수 없어요")
                    return
                }
                val intent = appIntent(activity, a.target)
                if (intent == null) toast(activity, "앱을 찾을 수 없어요: ${a.target}") else activity.startActivity(intent)
            }
        }
    }

    // Target is either "package" (its launcher activity) or "package/.Activity" / "package/full.Class".
    fun appIntent(activity: Activity, target: String): Intent? {
        if ('/' in target) {
            val cn = ComponentName.unflattenFromString(target) ?: return null
            return Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return activity.packageManager.getLaunchIntentForPackage(target)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun toast(activity: Activity, msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
