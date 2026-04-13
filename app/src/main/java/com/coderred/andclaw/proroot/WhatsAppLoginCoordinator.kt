package com.coderred.andclaw.proroot

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 앱 전역 WhatsApp 로그인 시도 동시성 가드.
 * (Settings/Dashboard에서 동시에 QR 로그인 RPC를 시작하는 것을 방지)
 */
object WhatsAppLoginCoordinator {
    private val inProgress = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inProgress.compareAndSet(false, true)

    fun release() {
        inProgress.set(false)
    }
}
