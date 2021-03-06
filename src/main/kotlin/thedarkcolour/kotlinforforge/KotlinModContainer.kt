package thedarkcolour.kotlinforforge

import net.minecraftforge.eventbus.EventBusErrorMessage
import net.minecraftforge.eventbus.api.BusBuilder
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.IEventListener
import net.minecraftforge.fml.LifecycleEventProvider.LifecycleEvent
import net.minecraftforge.fml.Logging
import net.minecraftforge.fml.ModContainer
import net.minecraftforge.fml.ModLoadingException
import net.minecraftforge.fml.ModLoadingStage
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.forgespi.language.IModInfo
import net.minecraftforge.forgespi.language.ModFileScanData
import thedarkcolour.kotlinforforge.eventbus.KotlinEventBus
import thedarkcolour.kotlinforforge.kotlin.supply
import java.util.function.Consumer

public typealias LifecycleEventListener = (LifecycleEvent) -> Unit

/**
 * The Kotlin for Forge `ModContainer`.
 */
public class KotlinModContainer(
        private val info: IModInfo,
        private val className: String,
        private val classLoader: ClassLoader,
        private val scanData: ModFileScanData,
) : ModContainer(info) {

    /**
     * The `@Mod` object or instance of the `@Mod` class.
     */
    private lateinit var modInstance: Any

    /**
     * The `IEventBus` for Kotlin for Forge mods
     * that supports `KCallable` event listeners.
     */
    public val eventBus: KotlinEventBus

    init {
        LOGGER.debug(Logging.LOADING, "Creating KotlinModContainer instance for {} with classLoader {} & {}", className, classLoader, javaClass.classLoader)
        triggerMap[ModLoadingStage.CONSTRUCT] = createTrigger(::constructMod, ::afterEvent)
        triggerMap[ModLoadingStage.CREATE_REGISTRIES] = createTrigger(::fireEvent, ::afterEvent)
        triggerMap[ModLoadingStage.LOAD_REGISTRIES] = createTrigger(::fireEvent, ::afterEvent)
        triggerMap[ModLoadingStage.COMMON_SETUP] = createTrigger(::fireEvent, ::afterEvent)
        triggerMap[ModLoadingStage.SIDED_SETUP] = createTrigger(::fireEvent, ::afterEvent)
        triggerMap[ModLoadingStage.ENQUEUE_IMC] = createTrigger(::fireEvent, ::afterEvent)
        triggerMap[ModLoadingStage.PROCESS_IMC] = createTrigger(::fireEvent, ::afterEvent)
        triggerMap[ModLoadingStage.COMPLETE] = createTrigger(::fireEvent, ::afterEvent)
        triggerMap[ModLoadingStage.GATHERDATA] = createTrigger(::fireEvent, ::afterEvent)
        eventBus = KotlinEventBus(BusBuilder.builder().setExceptionHandler(::onEventFailed).setTrackPhases(false))
        contextExtension = supply(KotlinModLoadingContext(this))
    }

    /**
     * Creates a single `Consumer` that calls
     * both [consumerA] and [consumerB].
     */
    private fun createTrigger(
            consumerA: LifecycleEventListener,
            consumerB: LifecycleEventListener,
    ): Consumer<LifecycleEvent> {
        return Consumer { event ->
            consumerA(event)
            consumerB(event)
        }
    }

    /**
     * The `IEventExceptionHandler` that logs
     * errors in events as errors.
     */
    private fun onEventFailed(iEventBus: IEventBus, event: Event, iEventListeners: Array<IEventListener>, i: Int, throwable: Throwable) {
        LOGGER.error(EventBusErrorMessage(event, i, iEventListeners, throwable))
    }

    /**
     * Fires a `LifecycleEvent` on the mod [eventBus].
     */
    private fun fireEvent(lifecycleEvent: LifecycleEvent) {
        val event = lifecycleEvent.getOrBuildEvent(this)

        LOGGER.debug(Logging.LOADING, "Firing event for modid $modId : $event")

        try {
            eventBus.post(event)
            LOGGER.debug(Logging.LOADING, "Fired event for modid $modId : $event")
        } catch (throwable: Throwable) {
            LOGGER.error(Logging.LOADING,"An error occurred while dispatching event ${lifecycleEvent.fromStage()} to $modId")
            throw ModLoadingException(modInfo, lifecycleEvent.fromStage(), "fml.modloading.errorduringevent", throwable)
        }
    }

    /**
     * If an error was thrown during the event,
     * log it to the console as an error.
     */
    private fun afterEvent(lifecycleEvent: LifecycleEvent) {
        if (currentState == ModLoadingStage.ERROR) {
            LOGGER.error(Logging.LOADING, "An error occurred while dispatching event ${lifecycleEvent.fromStage()} to $modId")
        }
    }

    /**
     * Initializes [modInstance] and calls the mod constructor
     */
    private fun constructMod(lifecycleEvent: LifecycleEvent) {
        val modClass: Class<*>

        try {
            modClass = Class.forName(className, false, classLoader)
            LOGGER.debug(Logging.LOADING, "Loaded kotlin modclass ${modClass.name} with ${modClass.classLoader}")
        } catch (throwable: Throwable) {
            LOGGER.error(Logging.LOADING, "Failed to load kotlin class $className", throwable)
            throw ModLoadingException(info, ModLoadingStage.CONSTRUCT, "fml.modloading.failedtoloadmodclass", throwable)
        }

        try {
            LOGGER.debug(Logging.LOADING, "Loading mod instance ${getModId()} of type ${modClass.name}")
            modInstance = modClass.kotlin.objectInstance ?: modClass.newInstance()
            LOGGER.debug(Logging.LOADING, "Loaded mod instance ${getModId()} of type ${modClass.name}")
        } catch (throwable: Throwable) {
            LOGGER.error(Logging.LOADING, "Failed to create mod instance. ModID: ${getModId()}, class ${modClass.name}", throwable)
            throw ModLoadingException(modInfo, lifecycleEvent.fromStage(), "fml.modloading.failedtoloadmod", throwable, modClass)
        }

        try {
            LOGGER.debug(Logging.LOADING, "Injecting Automatic Kotlin event subscribers for ${getModId()}")
            // Inject into object EventBusSubscribers
            AutoKotlinEventBusSubscriber.inject(this, scanData, modClass.classLoader)
            LOGGER.debug(Logging.LOADING, "Completed Automatic Kotlin event subscribers for ${getModId()}")
        } catch (throwable: Throwable) {
            LOGGER.error(Logging.LOADING, "Failed to register Automatic Kotlin subscribers. ModID: ${getModId()}, class ${modClass.name}", throwable)
            throw ModLoadingException(modInfo, lifecycleEvent.fromStage(), "fml.modloading.failedtoloadmod", throwable, modClass)
        }
    }

    override fun dispatchConfigEvent(event: ModConfig.ModConfigEvent) {
        eventBus.post(event)
    }

    override fun matches(mod: Any?): Boolean {
        return mod == modInstance
    }

    override fun getMod(): Any = modInstance

    override fun acceptEvent(e: Event) {
        eventBus.post(e)
    }
}