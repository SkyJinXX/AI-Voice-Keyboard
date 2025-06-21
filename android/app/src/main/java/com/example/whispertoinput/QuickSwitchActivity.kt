/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2024 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodInfo
import android.widget.Toast
import android.view.WindowManager
import android.view.Gravity

class QuickSwitchActivity : Activity() {
    
    companion object {
        private const val TAG = "QuickSwitchActivity"
    }
    
    private var hasShownPicker = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置窗口属性，确保Activity能够获得焦点并显示在最前面
        setupWindowProperties()
        
        Log.d(TAG, "QuickSwitchActivity started")
        Log.d(TAG, "Intent action: ${intent?.action}")
        Log.d(TAG, "Intent extras: ${intent?.extras}")
        
        // 记录当前输入法状态
        logCurrentIMEStatus()
        
        // 执行输入法切换逻辑
        performIMESwitch()
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "QuickSwitchActivity onResume, hasShownPicker: $hasShownPicker")
        
        // 如果已经显示过选择器，并且Activity重新获得焦点，说明用户已经完成选择
        if (hasShownPicker) {
            Log.d(TAG, "用户已完成输入法选择，关闭Activity")
            // 延迟一点再关闭，让用户看到切换效果
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                logCurrentIMEStatus()
                finish()
            }, 300)
        }
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "QuickSwitchActivity onPause")
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "QuickSwitchActivity onStop")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "QuickSwitchActivity onDestroy")
    }
    
    // 处理用户按返回键的情况
    override fun onBackPressed() {
        Log.d(TAG, "用户按下返回键，关闭Activity")
        super.onBackPressed()
    }
    
    // 处理Activity失去焦点但未被销毁的情况（比如显示了系统对话框）
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, hasShownPicker=$hasShownPicker")
        
        // 如果已经显示过选择器，并且重新获得焦点，说明选择器已关闭
        if (hasShownPicker && hasFocus) {
            Log.d(TAG, "输入法选择器已关闭，准备结束Activity")
            // 短暂延迟后关闭Activity，让系统有时间处理输入法切换
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "=== Final IME Status ===")
                logCurrentIMEStatus()
                finish()
            }, 500)
        }
    }
    
    private fun setupWindowProperties() {
        try {
            // 设置窗口属性，确保Activity能够显示在最前面并获得焦点
            window.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
            
            // 设置窗口类型为系统级别
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
            
            // 设置窗口位置和大小
            val layoutParams = window.attributes
            layoutParams.gravity = Gravity.CENTER
            layoutParams.width = 1
            layoutParams.height = 1
            window.attributes = layoutParams
            
            Log.d(TAG, "Window properties configured")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup window properties", e)
            // 继续执行，不影响核心功能
        }
    }
    
    private fun performIMESwitch() {
        try {
            Log.d(TAG, "=== Starting IME Switch Process ===")
            Log.d(TAG, "Current Android API Level: ${Build.VERSION.SDK_INT}")
            
            // 记录切换前的状态
            logCurrentIMEStatus()
            
            // 延迟一点执行，确保Activity完全初始化
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                switchToPreviousIME()
            }, 100)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during IME switch", e)
            Toast.makeText(this, "输入法切换失败", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun switchToPreviousIME() {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        
        Log.d(TAG, "Android系统限制：普通应用无法直接切换输入法")
        Log.d(TAG, "显示输入法选择器作为替代方案")
        
        try {
            // 确保Activity获得焦点
            window.decorView.requestFocus()
            
            // 显示输入法选择器
            val success = try {
                inputMethodManager.showInputMethodPicker()
                true
            } catch (e: Exception) {
                Log.w(TAG, "First attempt to show picker failed", e)
                false
            }
            
            if (success) {
                hasShownPicker = true
                // 给用户明确的提示
                Toast.makeText(this, "🔄 请选择要切换的输入法", Toast.LENGTH_LONG).show()
                Log.d(TAG, "输入法选择器已显示，等待用户选择...")
                
                // 设置一个超时机制，防止Activity永远不关闭
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing) {
                        Log.d(TAG, "超时未检测到用户操作，自动关闭Activity")
                        finish()
                    }
                }, 10000) // 10秒超时
                
            } else {
                // 如果第一次失败，尝试备用方案
                Log.d(TAG, "尝试备用方案：打开输入法设置")
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                Toast.makeText(this, "🔄 已打开输入法设置，请手动切换", Toast.LENGTH_LONG).show()
                finish()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "显示输入法选择器失败", e)
            Toast.makeText(this, "无法显示输入法选择器", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun logCurrentIMEStatus() {
        try {
            // 获取当前默认输入法
            val currentIME = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            Log.d(TAG, "Current default IME: $currentIME")
            
            // 获取输入法管理器
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            
            // 获取所有已启用的输入法
            val enabledIMEs = inputMethodManager.enabledInputMethodList
            Log.d(TAG, "Enabled IMEs count: ${enabledIMEs.size}")
            
            for (ime in enabledIMEs) {
                Log.d(TAG, "  - ${ime.id} (${ime.loadLabel(packageManager)})")
            }
            
            // 获取所有已安装的输入法
            val allIMEs = inputMethodManager.inputMethodList
            Log.d(TAG, "All installed IMEs count: ${allIMEs.size}")
            
            // 检查是否有上一个输入法可切换
            try {
                val hasLastIME = if (Build.VERSION.SDK_INT >= 28) {
                    // 对于新版本，我们无法直接检查，但可以尝试
                    enabledIMEs.size > 1
                } else {
                    // 对于旧版本，我们可以尝试检查
                    enabledIMEs.size > 1
                }
                Log.d(TAG, "Has previous IME to switch to: $hasLastIME")
            } catch (e: Exception) {
                Log.w(TAG, "Could not determine if previous IME exists", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error logging IME status", e)
        }
    }
} 