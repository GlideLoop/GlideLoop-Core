package skywolf46.glideloop.core.abstraction

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 발동 가능한 일회성 실행 가능 항수를 나타내는 Trigger 클래스입니다.
 * Trigger 클래스는 trigger() 호출시 폐기되며, 더 이상 사용할 수 없습니다.
 *
 * 해당 클래스는 익명 클래스로 사용하는것보다는, 식별할 수 있는 클래스로 상속하여 사용하는것이 추천됩니다.
 * 이 내용에 관련된것은 [skywolf46.glideloop.core.abstraction.UnregisterTrigger]을 참고하면 좋습니다.
 */
abstract class Trigger {
    private val isTriggered: AtomicBoolean = AtomicBoolean(false)

    fun canBeTriggered() = isTriggered.get()

    fun trigger() {
        if (isTriggered.get())
            return
        isTriggered.set(true)
        onTrigger()
    }

    protected abstract fun onTrigger()
}