package de.tubs.skeditor.property;


import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.gef.EditPart;
import org.eclipse.graphiti.mm.pictograms.PictogramElement;
import org.eclipse.graphiti.ui.editor.IDiagramContainerUI;
import org.eclipse.graphiti.ui.platform.GraphitiShapeEditPart;
import org.eclipse.ui.views.properties.IPropertySource;

import SkillGraph.Node;

/**
 * Registered to adapt an {@link EditPart} that gets selected in the
 * {@link IDiagramContainerUI} to A {@link IPropertySource} object that is used as
 * input for the properties view.
 */
public class GraphitiEditPartToIPropertySourceAdapterFactory implements IAdapterFactory {

	public GraphitiEditPartToIPropertySourceAdapterFactory() {
		super();
	}

	public Object getAdapter(Object adaptableObject, @SuppressWarnings("rawtypes") Class adapterType) {
		if (IPropertySource.class.equals(adapterType)) {
			if (adaptableObject instanceof GraphitiShapeEditPart) {
				GraphitiShapeEditPart editPart = (GraphitiShapeEditPart) adaptableObject;
				PictogramElement pictogramElement = editPart.getPictogramElement();
				Object object = editPart.getFeatureProvider().getBusinessObjectForPictogramElement(pictogramElement);
				if (object instanceof Node) {
					return new SkillPropertySource((Node) object);
				}
			}
		}
		return null;
	}

	@SuppressWarnings("rawtypes")
	public Class[] getAdapterList() {
		return new Class[] { IPropertySource.class };
	}
}
