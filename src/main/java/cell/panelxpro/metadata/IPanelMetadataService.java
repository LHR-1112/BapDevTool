package cell.panelxpro.metadata;

import bap.cells.Cells;
import cell.CellIntf;
import panelxpro.metadata.dto.RegisterFunctionResult;
import panelxpro.metadata.dto.ListFunctionsResult;
import panelxpro.metadata.dto.DomainInfoDto;
import panelxpro.metadata.dto.DtoGenerateResultDto;
import panelxpro.metadata.dto.PanelFieldDto;
import panelxpro.metadata.dto.PanelInfoDto;

import java.util.List;

public interface IPanelMetadataService extends CellIntf {

	public static IPanelMetadataService get() {
		return Holder.INSTANCE;
	}

	class Holder {
		private static final IPanelMetadataService INSTANCE = Cells.get(IPanelMetadataService.class);
	}

	public String ping();

	List<DomainInfoDto> listDomains() throws Exception;

	List<PanelInfoDto> listPanels(String domainCode) throws Exception;

	List<PanelFieldDto> getFormStructure(String domainCode, String panelCode) throws Exception;

	DtoGenerateResultDto generateDto(String panelCode, String domainCode) throws Exception;

	DtoGenerateResultDto generateAllDtos(String domainCode) throws Exception;

	DtoGenerateResultDto generateAllDataObjects(String domainCode) throws Exception;

	RegisterFunctionResult registerFunction(String domainCode, String className, String methodName) throws Exception;

	RegisterFunctionResult registerFunctions(String domainCode, String className) throws Exception;

	ListFunctionsResult listFunctions(String domainCode) throws Exception;
}
