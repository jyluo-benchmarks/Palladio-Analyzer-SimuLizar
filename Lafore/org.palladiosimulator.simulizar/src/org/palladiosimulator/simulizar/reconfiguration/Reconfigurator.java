package org.palladiosimulator.simulizar.reconfiguration;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;
import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.m2m.qvt.oml.BasicModelExtent;
import org.eclipse.m2m.qvt.oml.ExecutionContextImpl;
import org.eclipse.m2m.qvt.oml.ExecutionDiagnostic;
import org.eclipse.m2m.qvt.oml.ModelExtent;
import org.eclipse.m2m.qvt.oml.TransformationExecutor;
import org.palladiosimulator.commons.designpatterns.AbstractObservable;
import org.palladiosimulator.edp2.models.measuringpoint.MeasuringPoint;
import org.palladiosimulator.pcm.repository.Repository;
import org.palladiosimulator.runtimemeasurement.RuntimeMeasurement;
import org.palladiosimulator.runtimemeasurement.RuntimeMeasurementModel;
import org.palladiosimulator.runtimemeasurement.util.RuntimeMeasurementSwitch;
import org.palladiosimulator.simulizar.access.IModelAccess;
import org.palladiosimulator.simulizar.interpreter.listener.BeginReconfigurationEvent;
import org.palladiosimulator.simulizar.interpreter.listener.EndReconfigurationEvent;
import org.palladiosimulator.simulizar.lafore.eurema.operations.AnalyzeImplementation;
import org.palladiosimulator.simulizar.utils.FileUtil;

import de.mdelab.eurema.interpreter.EuremaInterpreter;
import de.mdelab.eurema.interpreter.EuremaInterpreterFactory;
import de.mdelab.eurema.interpreter.EventQueue;
import de.uka.ipd.sdq.simucomframework.model.SimuComModel;
import de.uka.ipd.sdq.simulation.abstractsimengine.ISimulationControl;
import eurema.EuremaFactory;
import strategies.RuntimeStrategiesModel;
import strategies.impl.StrategiesFactoryImpl;
import violations.RuntimeViolationsModel;
import violations.impl.ViolationsFactoryImpl;

/**
 * Class whose objects will listen on changes in the PCM@Runtime and trigger
 * reconfigurations respectively. The reconfiguration is executed with LAFORE.
 * LAFORE uses EUREMA feedback loop model and interprets it.
 * 
 * @author Steffen Becker
 * @author Matthias Becker
 * @author Sebastian Lehrig
 * @author Florian Rosenthal
 * @author Goran Piskachev
 *
 */
public class Reconfigurator extends AbstractObservable<IReconfigurationListener> {

	/**
	 * This class' internal LOGGER.
	 */
	private static final Logger LOGGER = Logger.getLogger(Reconfigurator.class);

	/**
	 * This is the EUREMA interpreter instance.
	 */
	private final EuremaInterpreter interpreter = EuremaInterpreterFactory.getInstance();
	/**
	 * This is a queue for events which trigger the interpretation of EUREMA.
	 */
	private EventQueue q;

	/**
	 * After executing a reconfiguration, current model changes within the PCM
	 * blackboard partition are tracked here.
	 */
	private final Collection<Notification> modelChanges = new LinkedList<Notification>();

	/**
	 * Change listener, which will convert selected changes in the
	 * RuntimeMeasurement instance into reconfiguration checks.
	 */
	private final Adapter runtimeMeasurementListener = new EContentAdapter() {

		@Override
		public void notifyChanged(final Notification notification) {
			super.notifyChanged(notification);
			modelChanges.clear();
			Reconfigurator.this.checkAndExecuteReconfigurations(notification);

			if (modelChanges.size() > 0) {
				Reconfigurator.this.getEventDispatcher().reconfigurationExecuted(modelChanges);

			}
		}

	};

	/**
	 * A log listener which logs all changes in the global PCM model.
	 */
	private final Adapter globalPCMChangeListener = new EContentAdapter() {

		@Override
		public void notifyChanged(final Notification notification) {
			super.notifyChanged(notification);
			if (notification.getEventType() != Notification.REMOVING_ADAPTER) {
				modelChanges.add(notification);
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Detected change in global PCM model. Changed object: " + notification.getNotifier());
					LOGGER.debug(notification.toString());
				}
			}
		}

	};

	/**
	 * Access interface to the global PCM@Runtime model.
	 */
	private final org.palladiosimulator.analyzer.workflow.blackboard.PCMResourceSetPartition pcmResourceSetPartition;

	/**
	 * Access interface to the RuntimeMeasurement model.
	 */
	private final RuntimeMeasurementModel runtimeMeasurementModel;

	/**
	 * Set of all registered reconfigurators, i.e., objects that can change the
	 * PCM@Runtime.
	 */
	private final List<IReconfigurator> reconfigurators;

	private final ISimulationControl simulationController;

	private final IModelAccess modelAccess;
	private final SimuComModel model;

	/**
	 * Constructor.
	 * 
	 * @param model
	 * 
	 * @param modelAccessFactory
	 *            Access factory for model access interfaces.
	 * @param reconfigurators
	 *            Set of reconfigurators which will be triggered as soon as new,
	 *            interesting monitoring data arrives.
	 */
	public Reconfigurator(SimuComModel model, final IModelAccess modelAccessFactory,
			final ISimulationControl simulationcontrol, final List<IReconfigurator> reconfigurators) {
		super();
		this.model = model;
		this.pcmResourceSetPartition = modelAccessFactory.getGlobalPCMModel();
		this.runtimeMeasurementModel = modelAccessFactory.getRuntimeMeasurementModel();
		this.reconfigurators = reconfigurators;
		this.simulationController = simulationcontrol;
		this.modelAccess = modelAccessFactory;

		initEurema();
	}

	/**
	 * Setup all listeners to listen for their respective model changes.
	 */
	public void startListening() {
		pcmResourceSetPartition.getResourceSet().eAdapters().add(this.globalPCMChangeListener);
		this.runtimeMeasurementModel.eAdapters().add(this.runtimeMeasurementListener);
	}

	/**
	 * Detach all model listeners.
	 */
	public void stopListening() {
		this.runtimeMeasurementModel.eAdapters().remove(this.runtimeMeasurementListener);
		pcmResourceSetPartition.getResourceSet().eAdapters().remove(this.globalPCMChangeListener);
	}

	/**
	 * Method which is called on a change in the RuntimeMeasurement. All
	 * reconfigurators are informed and can check for potential
	 * reconfigurations.
	 * 
	 * @param notification
	 *            The notification event, which describes a change in the
	 *            RuntimeMeasurement model.
	 */
	protected void checkAndExecuteReconfigurations(final Notification notification) {
		final EObject monitoredElement = this.getMonitoredElement(notification);

		// Value changed, reconfigure!

		if (isNotificationNewMeasurement(monitoredElement)) {
			// for (final IReconfigurator reconfigurator : this.reconfigurators)
			// {
			// double startReconfigurationTime = this.simulationController
			// .getCurrentSimulationTime();
			// if (reconfigurator.checkAndExecute(monitoredElement)) {
			// this.getEventDispatcher().beginReconfigurationEvent(
			// new ReconfigurationEvent(EventType.BEGIN,
			// startReconfigurationTime));
			// LOGGER.debug("Successfully executed reconfiguration.");
			// this.getEventDispatcher().endReconfigurationEvent(
			// new ReconfigurationEvent(EventType.END,
			// this.simulationController));
			// }
			// }

			// This call triggers the execution of LAFORE.
			triggerEurema();

			LOGGER.info("LAFORE Started! Simulation time: " + this.simulationController.getCurrentSimulationTime());
			// System.out.println("Simulation time: " +
			// this.simulationController.getCurrentSimulationTime());

		}

	}

	/**
	 * Method which is called to trigger the execution of LAFROE. It puts an
	 * event into the EUREMA queue.
	 * 
	 */
	public void triggerEurema() {
		eurema.Event startingEvent = EuremaFactory.eINSTANCE.createEvent();
		startingEvent.setName("StartMape");
		eurema.EventType startingEventType = EuremaFactory.eINSTANCE.createEventType();
		startingEventType.setType("StartMapeEvent"); // this is the type of
														// event defined in the
														// instance of EUREMA
		startingEvent.setType(startingEventType);

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// System.out.println("Added StartMape event.");
		removeDemands();

		q.add(startingEvent);
	}

	/**
	 * Method that executes the removeDemantd.qvto transformation. This
	 * transformation removed the demands of the Reconfigurator component in the
	 * PCM runtime model. These demands are added when each reconfiguration
	 * action is executed.
	 * 
	 */
	public void removeDemands() {
		List<EObject> pcmAllocation = Arrays.asList((EObject) modelAccess.getGlobalPCMModel().getAllocation());

		// FIXME: This is specific to the example. We should get the repository
		// in more general way.
		Repository r = null;
		for (Repository rTemp : modelAccess.getGlobalPCMModel().getRepositories()) {
			String b = rTemp.getEntityName();
			if (b.equals("znnRepository"))
				r = rTemp;
		}
		List<EObject> pcmRep = Arrays.asList((EObject) r);
		List<EObject> pcmSys = Arrays.asList((EObject) modelAccess.getGlobalPCMModel().getSystem());

		ModelExtent inAllocation = new BasicModelExtent(pcmAllocation);
		ModelExtent inRep = new BasicModelExtent(pcmRep);
		ModelExtent inSys = new BasicModelExtent(pcmSys);

		ExecutionContextImpl executionContext = new ExecutionContextImpl();
		URI uriqvto = URI.createFileURI(FileUtil.getAbsoluteFilename("org.palladiosimulator.simulizar",
				"reconfigurationDemands/removeDemands.qvto"));
		TransformationExecutor conflictCheckExecutor = new TransformationExecutor(uriqvto); // execute
																							// controller
																							// completion
																							// ExecutionDiagnostic
		ExecutionDiagnostic result = conflictCheckExecutor.execute(executionContext, inAllocation, inRep, inSys);
		LOGGER.info(" ======================================================= ");
		LOGGER.info("Removed reconfiguration demands!");

	}

	/**
	 * Method which locates the EUREMA model, in this case that is the Lafore
	 * instance. Then it loads the model for interpretation.
	 */
	public void initEurema() {

		// ResourceSet resourceSet = new ResourceSetImpl();
		//
		// URI platformPluginURI = URI.createFileURI(
		// getAbsoluteFilename("org.palladiosimulator.simulizar",
		// "EuremaLaforeMapeLoop/Lafore.eurema"));
		// Resource resource = resourceSet.createResource(platformPluginURI);
		// Architecture archi = (Architecture) resource.getContents().get(0);
		//
		// for (ModelResource modR : archi.getModelResourceSet().getResources())
		// {
		// if (modR.getName() == "MonitorRepository") {
		// URI a = null;
		// IFile ifile = ResourcesPlugin.getWorkspace().getRoot().getFile(new
		// Path(a.toPlatformString(true)));
		// // ifile.getRawLocation().makeAbsolute().toOSString();
		// modR.setURI(ifile.getFullPath().toOSString());
		// }
		// }
		//
		// resource.getContents().clear();
		// resource.getContents().add(archi);
		// try {
		// resource.save(Collections.emptyMap());
		// } catch (IOException e) {
		// e.printStackTrace();
		// }

		RuntimeViolationsModel vRuntime = ViolationsFactoryImpl.eINSTANCE.createRuntimeViolationsModel();
		RuntimeStrategiesModel sRuntime = StrategiesFactoryImpl.eINSTANCE.createRuntimeStrategiesModel();

		q = interpreter.execute(
				FileUtil.getAbsoluteFilename("org.palladiosimulator.simulizar", "EuremaLaforeMapeLoop/Lafore.eurema"),
				modelAccess, vRuntime, sRuntime);
		AnalyzeImplementation.numOfViolations = 0;
	}

	/**
	 * Checks whether the monitored element is not null
	 * 
	 * @param monitoredElement
	 * @return true if the monitored element is not null
	 */
	private boolean isNotificationNewMeasurement(final EObject monitoredElement) {
		return monitoredElement != null;
	}

	/**
	 * Visitor singleton which is used to query the monitored PCM object from a
	 * RuntimeMeasurement notification (which is a change in a
	 * {@link RuntimeMeasurement}).
	 */
	private static final RuntimeMeasurementSwitch<MeasuringPoint> MONITORED_ELEMENT_RETRIEVER = new RuntimeMeasurementSwitch<MeasuringPoint>() {

		@Override
		public MeasuringPoint caseRuntimeMeasurement(final RuntimeMeasurement object) {
			return object.getMeasuringPoint();
		};

	};

	void fireReconfigurationEvent(EndReconfigurationEvent endReconfigurationEvent) {
		this.getEventDispatcher().endReconfigurationEvent(endReconfigurationEvent);
	}

	void fireReconfigurationEvent(BeginReconfigurationEvent beginReconfigurationEvent) {
		this.getEventDispatcher().beginReconfigurationEvent(beginReconfigurationEvent);
	}

	/**
	 * Retrieve the monitored PCM element from the RuntimeMeasurement change
	 * event.
	 * 
	 * @param notification
	 *            The RuntimeMeasurment change event.
	 * @return The PCM element whose monitoring triggered the change event.
	 */
	protected EObject getMonitoredElement(final Notification notification) {
		switch (notification.getEventType()) {
		case Notification.ADD:
			return MONITORED_ELEMENT_RETRIEVER.doSwitch((EObject) notification.getNewValue());
		case Notification.REMOVE:
			return null;
		case Notification.REMOVING_ADAPTER:
			return null;
		case Notification.SET:
			// in this case, one feature such as value of notifier
			// PcmModelElementMeasurement could
			// have been set/changed
			return MONITORED_ELEMENT_RETRIEVER.doSwitch((EObject) notification.getNotifier());
		default:
			LOGGER.warn("Unsupported RuntimeMeasurement Notification: " + notification);
			return null;
		}
	}

}