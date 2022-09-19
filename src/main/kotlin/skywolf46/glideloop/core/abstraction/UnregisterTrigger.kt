package skywolf46.glideloop.core.abstraction

/**
 * 발동 가능한 일회성 등록 해제 함수를 나타내는 UnregisterTrigger입니다.
 * UnregisterTrigger 객체는 trigger() 사용시 해당 객체를 반환한 메서드의 작업이 정반대되는 역할을 하며, 한번 사용시 더 이상 사용할 수 없습니다.
 */
abstract class UnregisterTrigger : Trigger()