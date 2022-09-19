package skywolf46.glideloop.core.event

import skywolf46.glideloop.core.abstraction.UnregisterTrigger
import skywolf46.glideloop.core.annotation.ThreadSafe
import skywolf46.glideloop.core.exceptions.InaccessibleDataException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * 객체 기반 이벤트 호출 관리자, EventHandlerManager입니다.
 *
 * EventHandlerManager은 이벤트를 수신받는 <b>리시버(Receiver)</b>와 이벤트 객체인 <b>콜러(Caller)</b>로 이루어집니다.
 * 만약 사용자가 <b>콜러</b>의 이벤트 호출을 시도한다면, EventHandlerManager은 모든 우선순위에 기반해 가장 낮은 우선순위부터 높은 우선순위까지의 <b>리스너</b>를 순차적으로 호출합니다.
 *
 * 이벤트 호출중에 일어나는 모든 예외는 호출 메서드에 따라 처리 방식이 달라지며, 해당 처리 방식은 메서드 설명에서 확인이 가능합니다.
 */
class EventHandleManager {
    private val listener = AtomicReference<MutableMap<KClass<*>, EventHandleStorageReference<*>>>(mutableMapOf())
    private val lock = ReentrantLock()

    @Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")
    fun <T : Any> acquireEventStorage(klass: KClass<*>): EventHandleStorageReference<T> {
        return (listener.get()[klass] ?: insertStorage<T>(klass)) as EventHandleStorageReference<T>
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> insertStorage(klass: KClass<*>): EventHandleStorageReference<T> {
        lock.withLock {
            if (listener.get().containsKey(klass))
                return listener.get()[klass] as EventHandleStorageReference<T>
            val clonedMap = listener.get().toMutableMap()
            clonedMap[klass] = EventHandleStorageReference<T>()
            listener.set(clonedMap)
            return listener.get()[klass] as EventHandleStorageReference<T>
        }
    }

    fun callEvent(data: Any) {
        acquireEventStorage<Any>(data::class).callEvent(data)
    }

    fun <T : Any> registerListener(klass: KClass<T>, listener: (T) -> Unit): UnregisterTrigger {
        return acquireEventStorage<T>(klass).addEventListener(unit = listener)
    }


    fun <T : Any> registerListener(klass: KClass<T>, priority: Int, listener: (T) -> Unit): UnregisterTrigger {
        return acquireEventStorage<T>(klass).addEventListener(priority = priority, unit = listener)
    }

    /**
     * 이벤트 리시버 저장소, EventHandleStorageReference입니다.
     * 리시버 등록시, 일반적인 경우, EventHandleStorageReference에서는 AtomicReference를 통해 모든 스레드에 대한 이벤트 처리를 허용합니다.
     * 다만, 이벤트 최초 등록과 같은 경우처럼 많은 양의 이벤트 처리가 필요할때가 있을 경우, unsafe() 호출을 통해 EventHandleStorage의 원본 객체에 대한 접근이 가능합니다.
     * syncUnsafe() 메서드 호출시, 원본 객체에 대한 복사가 이루어지며 블럭 안에서 복사된 원본 객체의 접근이 허용됩니다.
     * syncUnsafe() 블럭을 벗어나면 EventHandleStorageReference의 원본 객체 업데이트가 진행되며, 업데이트된 객체에 대한 외부 접근은 불가능합니다.
     */
    class EventHandleStorageReference<T> {
        private val reference = AtomicReference(EventHandleStorage<T>())
        private val lock = ReentrantLock()

        /**
         * 이벤트 리시버를 새로 등록합니다.
         * 기본으로 등록되는 이벤트의 우선순위는 0입니다.
         */
        @ThreadSafe
        @JvmOverloads
        fun addEventListener(priority: Int = 0, unit: (T) -> Unit): UnregisterTrigger {
            return syncUnsafe {
                addEventListener(priority, unit)
            }
        }

        @ThreadSafe
        fun removeEventListener(priority: Int = 0, unit: (T) -> Unit): Boolean {
            return syncUnsafe {
                removeListenerFrom(priority, unit)
            }
        }

        @ThreadSafe
        fun removeEventListener(unit: (T) -> Unit): Boolean {
            return syncUnsafe {
                removeListener(unit)
            }
        }

        fun callEvent(data: Any) {
            data as T // 타입 체크. 실패시 이곳에서 오류 발생
            reference.get().iterateEventList {
                try {
                    it(data)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }

        /**
         * 이벤트 대량 등록 등 대량의 과부하가 일어날 수 있는 작업을 위해 지원되는 unsafe 메서드입니다.
         * unsafe류 메서드를 통해서만 EventHandleStorage에 대한 직접적인 접근으로 이벤트 삽입 및 제거가 가능합니다.
         * [unit] 파라미터가 완료된 후에는 더 이상 해당 EventHandleStorage의 접근이 불가능하며, 해당 객체가 원본 객체로 지정됩니다.
         *
         * 경고 : 해당 메서드는 스레드 안전하지만 가시성을 보장해주지 않으며, 결과값이 온전히 전달되지 않을 수 있습니다.
         * 온전한 값 전달은 syncUnsafe를 사용해야 합니다.
         */
        @ThreadSafe
        @Deprecated("해당 메서드는 가시성을 보장해주지 않으며, 단일 스레드에서 이 메서드를 사용한다는 확신이 있을때만 사용해야 합니다.")
        fun <R> unsafe(unit: EventHandleStorage<T>.() -> R): R {
            return reference.get().clone().let {
                val toReturn = unit(it)
                it.lockStorage()
                reference.set(it)
                return@let toReturn
            }
        }

        /**
         * 이벤트 대량 등록 등 대량의 과부하가 일어날 수 있는 작업을 위해 지원되는 syncUnsafe 메서드입니다.
         * unsafe류 메서드를 통해서만 EventHandleStorage에 대한 직접적인 접근으로 이벤트 삽입 및 제거가 가능합니다.
         * [unit] 파라미터가 완료된 후에는 더 이상 해당 EventHandleStorage의 접근이 불가능하며, 해당 객체가 원본 객체로 지정됩니다.
         *
         * 해당 메서드는 가시성을 보장합니다.
         */
        @ThreadSafe
        fun <R> syncUnsafe(unit: EventHandleStorage<T>.() -> R): R {
            return lock.withLock {
                reference.get().clone().let {
                    val toReturn = unit(it)
                    it.lockStorage()
                    reference.set(it)
                    return@let toReturn
                }
            }
        }
    }

    class EventHandleStorage<T> internal constructor() : Cloneable {
        private val isAccessible = AtomicBoolean(true)
        private val eventByPriorities = sortedMapOf<Int, MutableList<(T) -> Unit>>(Comparator.naturalOrder())

        internal fun lockStorage() {
            isAccessible.set(false)
        }

        @ThreadSafe
        fun acquireEventList(priority: Int): List<(T) -> Unit> {
            return eventByPriorities[priority] ?: emptyList()
        }

        @ThreadSafe
        fun iterateEventList(unit: ((T) -> Unit) -> Unit) {
            for (x in eventByPriorities) {
                for (y in x.value) {
                    unit(y)
                }
            }
        }

        @Suppress("MemberVisibilityCanBePrivate")
        fun addEventListener(priority: Int, data: (T) -> Unit): UnregisterTrigger {
            if (!isAccessible.get())
                throw InaccessibleDataException("이미 잠긴 이벤트 저장소입니다.")
            eventByPriorities.getOrPut(priority) { mutableListOf() }.add(data)
            return object : UnregisterTrigger() {
                override fun onTrigger() {
                    removeListenerFrom(priority, data)
                }
            }
        }

        @Suppress("MemberVisibilityCanBePrivate")
        fun addListeners(priority: Int, datas: List<(T) -> Unit>): UnregisterTrigger {
            if (!isAccessible.get())
                throw InaccessibleDataException("이미 잠긴 이벤트 저장소입니다.")
            eventByPriorities.getOrPut(priority) { mutableListOf() }.addAll(datas)
            return object : UnregisterTrigger() {
                val dataToRemove = datas.toList()
                override fun onTrigger() {
                    removeListenersFrom(priority, dataToRemove)
                }
            }
        }

        @Suppress("MemberVisibilityCanBePrivate")
        fun removeListenersFrom(priority: Int, data: List<(T) -> Unit>): Boolean {
            if (!isAccessible.get())
                throw InaccessibleDataException("이미 잠긴 이벤트 저장소입니다.")
            val list = eventByPriorities[priority] ?: return false
            var isRemoved = false
            for (x in data) {
                if (list.remove(x))
                    isRemoved = true
            }
            return isRemoved
        }

        @Suppress("MemberVisibilityCanBePrivate")
        fun removeListener(data: (T) -> Unit): Boolean {
            if (!isAccessible.get())
                throw InaccessibleDataException("이미 잠긴 이벤트 저장소입니다.")
            var isRemoved = false
            for (x in eventByPriorities.values) {
                if (x.remove(data))
                    isRemoved = true
            }
            return isRemoved
        }

        @Suppress("MemberVisibilityCanBePrivate")
        fun removeListenerFrom(priority: Int, data: (T) -> Unit): Boolean {
            if (!isAccessible.get())
                throw InaccessibleDataException("이미 잠긴 이벤트 저장소입니다.")
            return eventByPriorities[priority]?.remove(data) == true
        }


        @Suppress("MemberVisibilityCanBePrivate")
        fun removeListenerFrom(priority: Iterable<Int>, data: (T) -> Unit): Boolean {
            if (!isAccessible.get())
                throw InaccessibleDataException("이미 잠긴 이벤트 저장소입니다.")
            var isRemoved = false
            for (x in priority) {
                if (eventByPriorities[x]?.remove(data) == true)
                    isRemoved = true
            }
            return isRemoved
        }

        @Suppress("MemberVisibilityCanBePrivate")
        fun removeListenerFrom(priority: IntArray, data: (T) -> Unit): Boolean {
            return removeListenerFrom(priority.toList(), data)
        }

        @ThreadSafe
        public override fun clone(): EventHandleStorage<T> {
            return EventHandleStorage<T>().also {
                it.eventByPriorities.putAll(eventByPriorities)
            }
        }
    }

}