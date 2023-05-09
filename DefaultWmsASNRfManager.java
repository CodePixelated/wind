package com.vtradex.wms.rfserver.service.receiving.pojo;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.vtradex.edi.server.service.model.enums.SapConstants;
import com.vtradex.rf.common.RfConstant;
import com.vtradex.rf.common.exception.RfBusinessException;
import com.vtradex.thorn.server.exception.BusinessException;
import com.vtradex.thorn.server.model.EntityFactory;
import com.vtradex.thorn.server.model.security.ThornUser;
import com.vtradex.thorn.server.util.BeanUtils;
import com.vtradex.thorn.server.web.security.UserHolder;
import com.vtradex.wms.baserfserver.service.receiving.pojo.AbstractWmsASNRfManager;
import com.vtradex.wms.baseserver.holder.WmsWarehouseHolder;
import com.vtradex.wms.baseserver.holder.WmsWorkerHolder;
import com.vtradex.wms.baseserver.service.edi.WmsInInterfaceLogManager;
import com.vtradex.wms.baseserver.service.inventory.WmsInventoryPlusManager;
import com.vtradex.wms.baseserver.service.item.WmsItemManager;
import com.vtradex.wms.baseserver.service.receiveing.WmsASNManager;
import com.vtradex.wms.baseserver.utils.*;
import com.vtradex.wms.server.constant.WmsApiLogStatus;
import com.vtradex.wms.server.model.component.InventoryQty;
import com.vtradex.wms.server.model.component.LotInfo;
import com.vtradex.wms.server.model.entity.advanced.WmsErpWh;
import com.vtradex.wms.server.model.entity.advanced.WmsPnSup;
import com.vtradex.wms.server.model.entity.advanced.WmsSnTraceability;
import com.vtradex.wms.server.model.entity.cycleSample.WmsCycleSample;
import com.vtradex.wms.server.model.entity.inventory.*;
import com.vtradex.wms.server.model.entity.item.WmsInventoryState;
import com.vtradex.wms.server.model.entity.item.WmsItem;
import com.vtradex.wms.server.model.entity.item.WmsPackageUnit;
import com.vtradex.wms.server.model.entity.receiving.*;
import com.vtradex.wms.server.model.entity.warehouse.*;
import com.vtradex.wms.server.model.enums.WmsBillTypeCode;
import com.vtradex.wms.server.service.message.WmsAsynManager;
import com.vtradex.wms.server.service.message.pojo.SmsVocherNo;
import com.vtradex.wms.webservice.WebServiceSfc;
import com.vtradex.wms.webservice.pojo.DefaultWebServiceSfc;
import com.vtradex.wms.webservice.zwmsmigopostport.*;
import functions.rfc.sap.document.sap_com.ZWMS_PP_SN_IMPORTProxy;
import functions.rfc.sap.document.sap_com.ZWMS_SN_IMPORT_RETURN_S;
import functions.rfc.sap.document.sap_com.ZWMS_SN_IMPORT_S;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.util.ImageUtils;
import org.apache.tools.ant.util.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * rf收货处理类
 *
 * @author : <a href="yanjiang.qian@vtradex.net">钱艳江</a>
 * @since :2020/4/3 17:43
 */
@Service(value = "wmsASNRfManager")
@Transactional(readOnly = true)
public class DefaultWmsASNRfManager extends AbstractWmsASNRfManager {
	
	@Autowired
    protected WmsASNManager wmsASNManager;

    @Resource(name = "wmsAsynManager")
    protected WmsAsynManager wmsAsynManager;

    @Resource(name = "wmsInventoryPlusManager")
    WmsInventoryPlusManager WmsInventoryPlusManager;

    @Resource(name = "wmsItemManager")
    protected WmsItemManager wmsItemManager;

    @Autowired
    WmsInInterfaceLogManager inInterfaceLogManager;


    @Override
    public Map putQueryParams(Map params) {
        Long asnId = MapUtils.getLongNotNull(params, "id", "asn.id.is.null");
        Map result = MapUtils.createMapAndPutValue(RfConstant.FORWARD_VALUE, "success");
        /*Map<String, Object> result = new HashMap<>();
        result.put(RfConstant.FORWARD_VALUE, "success");*/
        result.put("asnId", asnId);
        return result;
    }

    @Override
    public Map showAsnDetailParams(Map params) {
        Map<String, Object> result = new HashMap<>();
        Long detailId = MapUtils.getLongNotNull(params, "id", "detail.id.is.null");
        Long asnId = MapUtils.getLongNotNull(params, "asnId", "asn.id.is.null");
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, detailId);
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        WmsItem item = this.commonDao.load(WmsItem.class, detail.getItemId());
        //获取asn单下所有物料
        List<WmsASNDetail> detailList = this.commonDao.findByQuery("from WmsASNDetail details where 1 = 1 and details.asnId = :asnId and details.itemId = :itemId ",
                new String[]{"asnId", "itemId"}, new Object[]{Long.valueOf(asnId), Long.valueOf(item.getId())});
        List<Long> asnIds = new ArrayList<>();
        /*for(WmsASNDetail detail:detailList){
            asnIds.add(detail.getItemId());
        }*/
        //asnIds = detailList.stream().map(WmsASNDetail::getItemId).collect(Collectors.toList());
        Double receivedQty = 0D;
        Double baseQty = 0D;
        for(WmsASNDetail detail1 : detailList){
            receivedQty += detail1.getReceivedQty();
            baseQty += detail1.getExpectedQty().getBaseQty();
        }
        params.put("batchInfo", "");//清除上一次扫描的批次
        params.put("scanCount", "");//清除上一次扫描的扫描次数
        params.put("itemCodeCount", "");//清除上一次扫描的数量
        params.put("huCode", "");//清除上一次扫描的HU
        params.put("scanCodeHuOld", "");//清除上一次扫描的连续HU
        params.put("receiveLocationId", "");//清除上一次扫描的库位
        params.put("remindHis", "");
        result.putAll(params);
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put("asnCode", asn.getCode());
        result.put("itemCode", item.getCode());
        result.put("detailId", detailId);
        result.put("asnId", asnId);
        result.put("receivedAndTotal", receivedQty + "/" + baseQty);
        return result;
    }

    @Override
    public Map getAsnReceivingInfo(Map params) {
        Map<String, Object> result = new HashMap<>();
        result.putAll(params);
        /*String scanCount = params.get("scanCount") == null ? "0" : params.get("scanCount").toString();
        String detailId = params.get(RfConstant.ENTITY_ID) == null ? null : params.get(RfConstant.ENTITY_ID).toString();
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        WmsItem item = this.commonDao.load(WmsItem.class, detail.getItemId());
        result.put("asnCode", asn.getCode());
        result.put("itemCode", item.getCode());
        result.put("receivedAndTotal", params.get("receivedAndTotal"));
        //result.put("huCode", params.get("huCode"));
        //result.put("batchInfo", params.get("batchInfoOld"));
        result.put("scanCount", scanCount);
        result.put("asdId", asn.getId());
        result.put("detailId", detail.getId());*/
        result.put(RfConstant.FORWARD_VALUE, "success");
        //params.clear();
        return result;
    }

    @Override
    @Transactional
    public Map findConfirm(Map params) {
        Map<String, Object> result = new HashMap<>();
        result.putAll(params);
        WmsWarehouse warehouse = WmsWarehouseHolder.get();

        result.put("remindHis", "1");

        String receiveLocation = MapUtils.getStringNotNull(params, "receiveLocationId", "收货库位为空");
        WmsLocation location = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'RV' ", "code", receiveLocation);
        if(null == location){
            throw new RfBusinessException("扫描的收货库位在系统中未维护！");
        }
        Long receiveLocationId = location.getId();


        //解析扫码HU的值
        String scanCodeHuNew = MapUtils.getStringNotNull(params, "scanCodeHu", "HU码为空");
        logger.info(UserHolder.getUser().getName() + ",原始扫描的HU为（" + scanCodeHuNew + "）========" + new Date());

        String itemCodeNew = "", quantityNew = "", batchInfoNew = "", scanHuNew = "";
        StringBuffer scanHu = new StringBuffer();
        StringBuffer scanItemCode = new StringBuffer();
        String [] scanCodeHuSplit = new String[0];
        if(scanCodeHuNew.length() <= 19){//扫码为条形码
            /*String inventoryHql = "from WmsInventory inventory where 1 = 1 and inventory.moveTool.boxNo = :scanHu ";
            WmsInventory wmsInventory = (WmsInventory)this.commonDao.findByQueryUniqueResult(inventoryHql, new String[]{"scanHu"}, new Object[]{scanCodeHuNew});
            Long itemId = wmsInventory.getSku().getItemId();*/
            throw new RfBusinessException("格式有误！");
        }else{
            if(StringUtils.isNotEmpty(scanCodeHuNew)){
                if(!scanCodeHuNew.contains("^")){
                    throw new RfBusinessException("格式有误！");
                }
                scanCodeHuSplit = scanCodeHuNew.split("\\^");
                //[0]-物料编码 [1]-数量 [2]-生产日期 [3]-批次号 [4]-一维码的前5位 [5]-一维码==HU码
                itemCodeNew = scanCodeHuSplit[0];
                quantityNew = scanCodeHuSplit[1];
                batchInfoNew = scanCodeHuSplit[3];
                scanHuNew = scanCodeHuSplit[5];
            }
        }
        if((params.get("scanCodeHuOld") == null ? "" : params.get("scanCodeHuOld").toString()).contains(scanHuNew)){
            throw new RfBusinessException("HU重复!");
        }

        String asnId = params.get("asnId") == null ? null : params.get("asnId").toString();
        String detailId = params.get("detailId") == null ? null : params.get("detailId").toString();
        //获取asn单下所有物料
        //List<WmsASNDetail> detailList = this.commonDao.findByQuery("from WmsASNDetail details where 1 = 1 and details.asnId = :asnId ", new String[]{"asnId"}, new Object[]{Long.valueOf(asnId)});
        //WmsASNDetail detail = (WmsASNDetail)commonDao.findByQueryUniqueResult("from WmsASNDetail detail where 1 = 1 and detail.asnId = :asnId and detail.id = :detailId",
        //       new String[]{"asnId","detailId"}, new Object[]{Long.valueOf(asnId), Long.valueOf(detailId)});
        List<Long> asnIds = new ArrayList<>();
        /*for(WmsASNDetail detail:detailList){
            asnIds.add(detail.getItemId());
        }*/
        //asnIds = detailList.stream().map(WmsASNDetail::getItemId).collect(Collectors.toList());

        //String detailId = params.get(RfConstant.ENTITY_ID) == null ? null : params.get(RfConstant.ENTITY_ID).toString();
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        WmsBillType billType = this.commonDao.load(WmsBillType.class, asn.getBillTypeId());
        String itemHql = "from WmsItem item where 1 = 1 and item.id in (:itemId) and item.code = :itemCode ";
        WmsItem wmsItem = (WmsItem)this.commonDao.findByQueryUniqueResult(itemHql, new String[]{"itemId", "itemCode"}, new Object[]{detail.getItemId(), itemCodeNew});
        if("TCQGDSH".equals(billType.getCode())){
            if(!(params.get("scanCodeHuOld").toString()).contains(itemCodeNew)){
                throw new RfBusinessException("扫描的HU的货品与第一次不一致！");
            }
        }
        if(!"TCQGDSH".equals(billType.getCode())) {
            if (null == wmsItem) {
                throw new RfBusinessException("料号不存在！");
            }
        }
        if(WmsBillTypeCode.CGSH.equals(billType.getCode())){
            String hql = "select sum(detail.expectedQty.baseQty) from WmsASNDetail detail where detail.itemId = :itemId and detail.factoryId = :factoryId and detail.asnId = :asnId";
            Double baseQty = (Double) commonDao.findByQueryUniqueResult(hql, new String[]{"itemId", "factoryId", "asnId"}, new Object[]{detail.getItemId(), detail.getFactoryId(), detail.getAsnId()});
            if(Double.valueOf(quantityNew) > baseQty){
                throw new RfBusinessException("扫描的HU数量大于计划数量！");
            }
        }else{
            if(Double.valueOf(quantityNew) > detail.getExpectedQty().getBaseQty()){
                throw new RfBusinessException("扫描的HU数量大于计划数量！");
            }
        }

        logger.info(UserHolder.getUser().getName() + ",解析后的HU为（" + scanHuNew + "）========,货品（" + itemCodeNew + "）,数量（" + quantityNew + "）,ASN单的货品[" + wmsItem.getCode() + "],[" + detail.getId() + "]" + new Date());
        //String inventoryHql = "from WmsInventory inventory where 1 = 1  and inventory.sku.itemId = :itemId and inventory.moveTool.boxNo = :scanHu and inventory.warehouseId = :warehouseId ";
        //List<WmsInventory> inventoryList = this.commonDao.findByQuery(inventoryHql, new String[]{"itemId","scanHu","warehouseId"}, new Object[]{wmsItem.getId(), scanHuNew, warehouse.getId()});
        String inventoryHql = "from WmsInventory inventory where 1 = 1 and inventory.moveTool.boxNo = :scanHu and inventory.qty.baseQty > 0 ";
        List<WmsInventory> inventoryList = this.commonDao.findByQuery(inventoryHql, "scanHu", scanHuNew);
        if(inventoryList.size() != 0){
            throw new RfBusinessException(scanCodeHuSplit[5] + "（HU）库存已存在！");
        }

        int scanCount = Integer.valueOf((params.get("scanCount")==null||"".equals(params.get("scanCount")))?"0":params.get("scanCount").toString()) + 1;
        params.put("quantityOld", quantityNew);
        params.put("batchInfoOld", batchInfoNew);
        params.put("scanCount", scanCount);

        String scanCodeHuHis = params.get("scanCodeHuOld") == null ? "" : params.get("scanCodeHuOld").toString();
        if(StringUtils.isEmpty(scanCodeHuHis) && StringUtils.isEmpty(scanCodeHuNew)){
            throw new RfBusinessException("HU码为空");
        }
        if(StringUtils.isEmpty(scanCodeHuHis)){
            if(params.get("scanCodeHuOld") == null){
                //result.put("scanCodeHuOld", scanItemCode.append(itemCodeNew).append(",").append(quantityNew).append(",").append(scanHuNew) + "|");
                result.put("scanCodeHuOld", scanCodeHuNew + "|");
            }else{
                //result.put("scanCodeHuOld", params.get("scanCodeHuOld").toString() + scanItemCode.append(itemCodeNew).append(",").append(quantityNew).append(",").append(scanHuNew) + "|");
                result.put("scanCodeHuOld", params.get("scanCodeHuOld").toString() + scanCodeHuNew + "|");
            }
        }else{
            if (!scanCodeHuHis.contains(scanHuNew)) {
                if(params.get("scanCodeHuOld") == null){
                    //result.put("scanCodeHuOld", scanItemCode.append(itemCodeNew).append(",").append(quantityNew).append(",").append(scanHuNew) + "|");
                    result.put("scanCodeHuOld", scanCodeHuNew + "|");
                }else{
                    //result.put("scanCodeHuOld", params.get("scanCodeHuOld").toString() + scanItemCode.append(itemCodeNew).append(",").append(quantityNew).append(",").append(scanHuNew) + "|");
                    result.put("scanCodeHuOld", params.get("scanCodeHuOld").toString() + scanCodeHuNew + "|");
                }
            }
        }

        String scanCodeType = params.get("scanCodeType") == null ? "" : params.get("scanCodeType").toString();//扫码类型
        String innerBoxNo = "", scanOutBoxNo = "", scanPalletNo = "", scanTypeHu = "";
        if("1".equals(scanCodeType)){//扫码内箱
            innerBoxNo = params.get("scanCodeContainer") == null ? "" : params.get("scanCodeContainer").toString();//扫码类型-内箱值
            if(!"A".equals(innerBoxNo.substring(0, 1))){
                throw new RfBusinessException("扫描的不是内箱码格式");
            }
            result.put("scanCodeContainer", innerBoxNo);
        }else if("2".equals(scanCodeType)){//扫码外箱
            scanOutBoxNo = params.get("scanCodeContainer") == null ? "" : params.get("scanCodeContainer").toString();//扫码类型-外箱值
            if(!"B".equals(scanOutBoxNo.substring(0, 1))){
                throw new RfBusinessException("扫描的不是外箱码格式");
            }
            result.put("scanCodeContainer", scanOutBoxNo);
        }else if("3".equals(scanCodeType)){//扫码托盘
            scanPalletNo = params.get("scanCodeContainer") == null ? "" : params.get("scanCodeContainer").toString();//扫码类型-托盘值
            if(!"C".equals(scanPalletNo.substring(0, 1))){
                throw new RfBusinessException("扫描的不是托盘码格式");
            }
            result.put("scanCodeContainer", scanPalletNo);
        }else if("4".equals(scanCodeType)){//扫码HU
            scanTypeHu = params.get("scanCodeContainer") == null ? "" : params.get("scanCodeContainer").toString();//扫码类型-HU值
            result.put("scanCodeContainer", scanTypeHu);
        }

        //累加每个HU的数量
        Double count = (params.get("itemCodeCount") == null || "".equals(params.get("itemCodeCount"))) ? 0 : Double.valueOf(params.get("itemCodeCount").toString());
        Double itemCodeCount = count + Double.valueOf(quantityNew);
        //相同物料的总数量
        String receivedAndTotal = params.get("receivedAndTotal").toString();
        String [] receivedAndTotals = receivedAndTotal.split("\\/");
        if(itemCodeCount > Double.valueOf(receivedAndTotals[1])){
            throw new RfBusinessException("累计扫的HU超出此次收货的数量！");
        }

        result.put("itemCode", itemCodeNew);
        result.put("quantity", quantityNew);
        result.put("itemCodeCount", itemCodeCount);
        result.put("batchInfo", batchInfoNew);
        result.put("scanCount", scanCount);
        result.put("huCode", scanHuNew);
        result.put("asnCode", asn.getCode());
        result.put("receiveLocationId", receiveLocation);//收货库位
        result.put("receiveLocationCode", receiveLocationId);
        result.put("scanCodeHu", "");//把当次扫的清空
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    @Override
    @Transactional
    public Map containerConfirmReceiving(Map params) {
        Map<String, Object> result = new HashMap<>();
        //String detailId = params.get("detailId") == null ? null : params.get("detailId").toString();
        String detailId = params.get(RfConstant.ENTITY_ID) == null ? null : params.get(RfConstant.ENTITY_ID).toString();
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());

        Long receiveLocationCode = params.get("receiveLocationCode") == null ? asn.getReceiveLocationId()
                : Long.valueOf(params.get("receiveLocationCode").toString());

        params.put("receiveLocationId", receiveLocationCode);

        //进行收货
        receivingrcAsnDetail(params);

        if (!"finishAll".equals(result.get(RfConstant.FORWARD_VALUE))) {
            result.put(RfConstant.FORWARD_VALUE, "newContainer");
            result.put(RfConstant.ENTITY_ID, asn.getId());
            result.put("container", "");
        }
        //result.put(RfConstant.CLEAR_VALUE, Boolean.TRUE);
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put("asnId",detail.getAsnId());
        return result;
    }


    @Override
    public Map putQuerySNParams(Map params) {
        Map<String, Object> result = new HashMap<>();
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put("asnId", params.get(RfConstant.ENTITY_ID));
        return result;
    }

    @Override
    public Map showAsnDetailSNParams(Map params) {
        Map<String, Object> result = new HashMap<>();
        //String detailId = params.get("detailId") == null ? null : params.get("detailId").toString();
        String detailId = params.get(RfConstant.ENTITY_ID) == null ? null : params.get(RfConstant.ENTITY_ID).toString();
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        WmsItem item = this.commonDao.load(WmsItem.class, detail.getItemId());
        params.put("scanCount", "");//清除上一次扫描的扫描次数
        params.put("allHu", "");//清除上一次扫描的HU
        params.put("pnAndSnAndLocaOld", "");//清除上一次扫描的连续HU
        params.put("receiveLocationId", "");//清除上一次扫描的库位
        params.put("receiveLocationCode", "");//清除上一次扫描的库位
        params.put("chooseType", "");//清除上一次扫描类型
        params.put("remind", "");
        result.putAll(params);
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put("asnCode", asn.getCode());
        result.put("itemCode", item.getCode());
        result.put("detailId", detailId);
        result.put("receivedAndTotal", detail.getReceivedQty() + "/" + detail.getExpectedQty().getBaseQty());
        return result;
    }

    @Override
    public Map getAsnReceivingSNInfo(Map params) {
        Map<String, Object> result = new HashMap<>();
        /*String scanCount = params.get("scanCount") == null ? "0" : params.get("scanCount").toString();
        String itemCode = params.get("itemCode") == null ? null : params.get("itemCode").toString();
        String detailId = params.get(RfConstant.ENTITY_ID) == null ? null : params.get(RfConstant.ENTITY_ID).toString();
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        String a = "FROM WmsLocation receiveLocation WHERE 1=1 AND receiveLocation.status = 'E' AND receiveLocation.type in ('RV','ST') " +
                "AND receiveLocation.warehouseId in (select asn.warehouseId from WmsASNDetail detail left join WmsASN asn on asn.id = detail.asnId where detail.id = :id )";
        List<WmsLocation> location = commonDao.findByQuery(a, "id", Long.valueOf(detailId));
        result.put("receiveLocationId", location.get(0).getId() + "-" + location.get(0).getCode());
        result.put("asnCode", asn.getCode());
        result.put("itemCode", itemCode);
        result.put("receivedAndTotal", params.get("receivedAndTotal"));
        result.put("scanCount", scanCount);
        result.put("allHu",params.get("allHu"));*/
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.putAll(params);
        return result;
    }


    @Override
    @Transactional
    public Map findSNConfirm(Map params) {
        Map<String, Object> result = new HashMap<>();
        result.putAll(params);
        WmsWarehouse wmsWarehouse = WmsWarehouseHolder.get();
        //解析扫二维码的值
        String scanCodeNew = params.get("scanCode") == null ? "" : params.get("scanCode").toString();//hu
        String scanCodePnNew = params.get("scanCodePn") == null ? null : params.get("scanCodePn").toString();//扫pn
        String scanCodeSnNew = params.get("scanCodeSn") == null ? null : params.get("scanCodeSn").toString();//扫sn
        if(StringUtils.isEmpty(scanCodeNew)){
            scanCodePnNew= ImgUtils.formatPN2(scanCodePnNew);
            scanCodeSnNew= ImgUtils.formatPN2(scanCodeSnNew);
        }
        String receiveLocation = MapUtils.getStringNotNull(params, "receiveLocationId", "收货库位为空");
        String itemCode = params.get("itemCode") == null ? null : params.get("itemCode").toString();
        Long asnId = params.get("asnId") == null ? 0l : Long.parseLong(params.get("asnId").toString());//hu -> 1171
        String detailId = params.get(RfConstant.ENTITY_ID) == null ? null : params.get(RfConstant.ENTITY_ID).toString();
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
       // WmsItem jyfhItem = this.commonDao.load(WmsItem.class, detail.getItemId());
        WmsItem item = this.commonDao.load(WmsItem.class, detail.getItemId());
        WmsASN asn = commonDao.load(WmsASN.class,asnId);
        WmsBillType bt = commonDao.load(WmsBillType.class,asn.getBillTypeId());
        if("JYFH".equals(bt.getCode())){//还料单据类型,验证还料的SN要在借料中存在
            if(StringUtils.isNotBlank(scanCodeNew)){
                checkPicktictet(detail,scanCodeNew.split("\\ ")[0],scanCodeNew.split("\\ ")[1]);
            }else{
                checkPicktictet(detail,scanCodePnNew,scanCodeSnNew);
            }

        }
        if(WmsBillTypeCode.TCQGDSH.equals(bt.getCode())){
            if(scanCodeNew.contains(" ")){
                throw new RfBusinessException("请按HU收货！");
            }
        }
        WmsLocation location = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'RV' ", "code", receiveLocation);
        if(null == location){
            throw new RfBusinessException("扫描的收货库位在系统中未维护！");
        }
        Long receiveLocationId = location.getId();
        if(StringUtils.isEmpty(scanCodeNew) && StringUtils.isEmpty(scanCodeSnNew)){
            throw new RfBusinessException("扫的二维码或者扫码SN不能为空！");
        }

        String scanCodePn = "", scanCodeSn = "";
        String [] scanCodeHuSplit = new String[0];
        StringBuffer scanCodePnOld = new StringBuffer();
        StringBuffer scanCodeSnOld = new StringBuffer();
        StringBuffer pnAndSnAndLocaOld = new StringBuffer();
        String boxHql = "select box from WmsBox box left join WmsInventory inventory on inventory.id = box.inventoryId left join WmsItem item on item.id = inventory.sku.itemId where 1 = 1 and box.snNumber = :scanCodeSn " +
                "and inventory.sku.itemId = :itemId and box.status != 'S' ";
        if(StringUtils.isNotEmpty(scanCodeNew)){
            String moveToolqrCode = result.get("moveToolqrCode") == null ? "" : result.get("moveToolqrCode").toString();
            //扫描HU。通过【sn绑定hu管理】找到hu下所绑定的所有sn
            if(!scanCodeNew.contains(" ")){
                getInterface(scanCodeNew, asn, detail, result);
                if(moveToolqrCode.contains(scanCodeNew)){
                    throw new RfBusinessException("HU重复！");
                }
                result.put("moveToolqrCode", moveToolqrCode + "," + scanCodeNew);//如果扫的是绑托的二维码，存起来后续收货完之后写进库存对应的MoveTool
            }else{
                if(WmsBillTypeCode.GDSH.equals(bt.getCode()) || WmsBillTypeCode.TCQGDSH.equals(bt.getCode())){
                    //判断扫描单体二维码(pn sn)
                    int space = scanCodeNew.split("\\ ").length - 1;
                    if(space > 1){
                        throw new RfBusinessException("扫描的[" + scanCodeNew + "]二维码格式不正确");
                    }
                    if(moveToolqrCode.contains(scanCodeNew)){
                        throw new RfBusinessException("扫的二维码重复！");
                    }
                    scanCodeHuSplit = scanCodeNew.split("\\ ");
                    //[0]-pn or 料号 [1]-sn
                    scanCodePn = scanCodeHuSplit[0];
                    scanCodeSn = scanCodeHuSplit[1];
                    result.put("moveToolqrCode", moveToolqrCode + "," + scanCodeNew);//如果扫的是绑托的二维码，存起来后续收货完之后写进库存对应的MoveTool


                    String findSnTr = "select st from WmsSnTraceability st where st.serialNo=:sn and st.itemId = :itemId and st.status = 'E' and st.postStatus = 'NP' ";
                    List<WmsSnTraceability> snTraceabilityList = commonDao.findByQuery(findSnTr,new String[]{"sn","itemId"},new Object[]{scanCodeSn,detail.getItemId()});
                    if (snTraceabilityList.size() <1){
                        throw new RfBusinessException("该SN[" + scanCodeSn +"]未在SN溯源记录表中维护！");
                    }

                    //if(WmsBillTypeCode.GDSH.equals(bt.getCode())){
                        if(!itemCode.equals(scanCodePn)){
                            String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
                            WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{itemCode, scanCodePn});
                            if(!scanCodePn.equals(itemCode)){
                                if(null == wmsPnSup){
                                    throw new RfBusinessException("PN与料号对应错误！");
                                }
                            }
                        }
                        WmsBox wmsBox;
                        if(StringUtils.isNotEmpty(scanCodeNew)){
                            wmsBox = (WmsBox)this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{scanCodeSn, item.getId()});
                        }else{
                            wmsBox = (WmsBox)this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{scanCodeSnNew, item.getId()});
                        }
                        if(null != wmsBox){
                            throw new RfBusinessException("物料["+item.getCode()+"]与SN["+scanCodeSn+"]已在库");
                        }
                    //}

                    if(StringUtils.isEmpty(scanCodeNew)){
                        if(scanCodeSnNew.equals(scanCodePnNew)){
                            throw new RfBusinessException("SN与PN相同！");
                        }
                    }

                }else{
                    scanCodeHuSplit = scanCodeNew.split("\\ ");
                    //[0]-pn or 料号 [1]-sn
                    scanCodePn = scanCodeHuSplit[0];
                    scanCodeSn = scanCodeHuSplit[1];
                    if(moveToolqrCode.contains(scanCodeNew)){
                        throw new RfBusinessException("HU重复！");
                    }
                    if(!itemCode.equals(scanCodePn)){
                        System.out.println("选中的货品="+itemCode);
                        String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
                        WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{itemCode, scanCodePn});
                        if(!scanCodePn.equals(itemCode)){
                            if(null == wmsPnSup){
                                throw new RfBusinessException("PN与料号对应错误！");
                            }
                        }
                    }
                    result.put("moveToolqrCode", moveToolqrCode + "," + scanCodeNew);//如果扫的是绑托的二维码，存起来后续收货完之后写进库存对应的MoveTool
                }
                WmsBox wmsBox;
                if(StringUtils.isNotEmpty(scanCodeNew)){
                    scanCodeHuSplit = scanCodeNew.split("\\ ");
                    //[0]-pn or 料号 [1]-sn
                    scanCodePn = scanCodeHuSplit[0];
                    scanCodeSn = scanCodeHuSplit[1];
                    wmsBox = (WmsBox)this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{scanCodeSn, item.getId()});
                }else{
                    wmsBox = (WmsBox)this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{scanCodeSnNew, item.getId()});
                }
                if(null != wmsBox){
                    throw new RfBusinessException("物料["+item.getCode()+"]与SN["+scanCodeSn+"]已在库");
                }
            }
        }else{
            scanCodePn = scanCodePnNew;
            scanCodeSn = scanCodeSnNew;
            if(scanCodeSn.equals(scanCodePn)){
                throw new RfBusinessException("SN与PN相同！");
            }
            if(!itemCode.equals(scanCodePn)){
                String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
                WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{itemCode, scanCodePn});
                if(!scanCodePn.equals(itemCode)){
                    if(null == wmsPnSup){
                        throw new RfBusinessException("PN与料号对应错误！");
                    }
                }
            }
            WmsBox wmsBox = (WmsBox)this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{scanCodeSn, item.getId()});
            if(null != wmsBox){
                throw new RfBusinessException("物料["+item.getCode()+"]与SN["+scanCodeSn+"]已在库");
            }
        }

        if(StringUtils.isEmpty(scanCodeNew)){
            if (WmsBillTypeCode.GDSH.equals(bt.getCode()) || WmsBillTypeCode.TCQGDSH.equals(bt.getCode())){
                if(StringUtils.isEmpty(scanCodeNew)){
                    if(scanCodeSnNew.equals(scanCodePnNew)){
                        throw new RfBusinessException("SN与PN相同！");
                    }
                }
                //String findSnTr = "select st from WmsSnTraceability st where st.serialNo=:sn AND st.itemId = (SELECT max(ps.itemId) FROM WmsPnSup ps left join WmsItem i on i.id = ps.itemId where i.code =:pn or ps.pn = :pn)";
                String findSnTr = "select st from WmsSnTraceability st where st.serialNo=:sn and st.itemId = :itemId and st.status = 'E' and st.postStatus = 'NP' ";
                //校验物料和SN是否在库，如果存在则提示‘物料与SN已在库’params.get("scanCodeSnOld")
                List<WmsSnTraceability> snTraceabilityList = new ArrayList<>();
                if(scanCodePn.equals(item.getCode())){
                    snTraceabilityList = commonDao.findByQuery(findSnTr,new String[]{"sn","itemId"},new Object[]{scanCodeSn,detail.getItemId()});
                }else{
                    String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
                    WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{itemCode, scanCodePn});
                    if(!scanCodePn.equals(itemCode)){
                        if(null == wmsPnSup){
                            throw new RfBusinessException("PN与料号对应错误！");
                        }
                    }
                    //WmsItem wmsItem = commonDao.load(WmsItem.class, wmsPnSup.getItemId());
                    snTraceabilityList = commonDao.findByQuery(findSnTr,new String[]{"sn","itemId"},new Object[]{scanCodeSn,detail.getItemId()});
                }

                if (snTraceabilityList.size() <1){
                    throw new RfBusinessException("该SN["+ scanCodeSn +"]未在SN溯源记录表中维护！");
                }
            }
        }

        int scanCount = Integer.valueOf((params.get("scanCount")==null||"".equals(params.get("scanCount")))?"0":params.get("scanCount").toString()) + 1;
        if(scanCount > detail.getExpectedQty().getBaseQty()){
            throw new RfBusinessException("累计扫描的SN数量超出单位数量！");
        }

        if(StringUtils.isNotEmpty(scanCodeNew)){
            if(scanCodeNew.contains(" ")){
                if (!"TCQGDSH".equals(bt.getCode())){
                    if(null == result.get("pnAndSnAndLocaOld")){
                        result.put("pnAndSnAndLocaOld", scanCodePn + "," + scanCodeSn + "," + scanCodeNew + "|");
                    }else{
                        result.put("pnAndSnAndLocaOld", result.get("pnAndSnAndLocaOld") + scanCodePn + "," + scanCodeSn + "," + scanCodeNew + "|");
                    }
                }else {
                    if(null == result.get("pnAndSnAndLocaOld")){
                        result.put("pnAndSnAndLocaOld", scanCodePn + "," + scanCodeSn + "," + scanCodeNew + "|");
                    }else{
                        if(result.get("pnAndSnAndLocaOld").toString().indexOf(scanCodePn+","+scanCodeSn)!=-1){
                            throw new RfBusinessException("扫描了重复的pn("+scanCodePn+")和sn("+scanCodeSn+")");
                        }
                        result.put("pnAndSnAndLocaOld", result.get("pnAndSnAndLocaOld") + scanCodePn + "," + scanCodeSn + "," + scanCodeNew + "|");
                    }
                }
            }
        }else{
            if(null == result.get("pnAndSnAndLocaOld")){
                result.put("pnAndSnAndLocaOld", scanCodePn + "," + scanCodeSn +  "," + scanCodeNew + "|");
            }else{
                if(result.get("pnAndSnAndLocaOld").toString().indexOf(scanCodePn+","+scanCodeSn)!=-1){
                    throw new RfBusinessException("扫描了重复的pn("+scanCodePn+")和sn("+scanCodeSn+")");
                }
                result.put("pnAndSnAndLocaOld", result.get("pnAndSnAndLocaOld") + scanCodePn + "," + scanCodeSn +  "," + scanCodeNew + "|");
            }
        }
        String allHu=result.get("allHu")==null?"":result.get("allHu")+"";
        if("".equals(allHu)){
            allHu=scanCodeNew;
        }else{
            allHu+=","+scanCodeNew;
        }
        int baseQty = 0;
        if(StringUtils.isEmpty(scanCodeNew)){
            baseQty = 1;
        }else{
            String a = result.get("pnAndSnAndLocaOld") == null ? "" : result.get("pnAndSnAndLocaOld").toString();
            String [] as = a.split("\\|");
            baseQty = as.length;
        }
        result.put("allHu",allHu);
        result.put("scanCount", scanCount);
        result.put("baseQty", baseQty);
        result.put("asnCode", asn.getCode());
        result.put("scanCodePn", "");//清空PN
        result.put("scanCodeSn", "");//清空SN
        result.put("scanCode", "");//清空二维码
        result.put("receiveLocationId", receiveLocation);//收货库位
        result.put("receiveLocationCode", receiveLocationId);
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    private List<WmsInventory> checkPicktictet(WmsASNDetail detail,String pn,String sn){
        String hql="select erp from WmsErpWh erp,WmsSupplier wmsSupplier where  wmsSupplier.code=erp.inventoryPlace " +
                " and wmsSupplier.erpCode=erp.code " +
                " and wmsSupplier.id=:wmsSupplierId";
        List<WmsErpWh> erpWhList=this.commonDao.findByQuery(hql,"wmsSupplierId",detail.getLotInfo().getSupplierId());
        if(null==erpWhList||erpWhList.size()==0){
            throw new RfBusinessException("供应商ID("+detail.getLotInfo().getSupplierId()+")找不到对应erp逻辑仓库");
        }
        WmsErpWh wmsErpWh=erpWhList.get(0);
        hql="select inventory FROM WmsBox box LEFT JOIN WmsInventory inventory ON inventory.id =  box.inventoryId " +
                " left join WmsItemKey itemKey on itemKey.id=inventory.sku.itemKeyId " +
                " left join WmsLocation location on inventory.locationId=location.id  where " +
                "  location.code='GYSKW' and inventory.qty.baseQty>0 and box.snNumber=:snNumber and inventory.pn=:pn and inventory.sku.itemId=:itemId "+
                " and itemKey.lotInfo.erpId=:erpId";
        List<WmsInventory> list=this.commonDao.findByQuery(hql,new String[]{"snNumber","pn","itemId","erpId"},
                new Object[]{sn,pn,detail.getItemId(),wmsErpWh.getId()});
        if(null==list||list.size()==0){
            throw new RfBusinessException("根据pn("+pn+")和sn("+sn+")查询库位编码(GYSKW)库存为空！");
        }
        return list;
    }

    @Override
    @Transactional
    public Map containerConfirmSNReceiving(Map params) {
        Map<String, Object> result = new HashMap<>();
        WmsWarehouse wmsWarehouse = WmsWarehouseHolder.get();
        //String detailId = params.get("detailId") == null ? null : params.get("detailId").toString();
        String detailId = params.get(RfConstant.ENTITY_ID) == null ? null : params.get(RfConstant.ENTITY_ID).toString();
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());

        Long receiveLocationCode = params.get("receiveLocationCode") == null ? asn.getReceiveLocationId()
                : Long.valueOf(params.get("receiveLocationCode").toString());
        params.put("receiveLocationId", receiveLocationCode);

        //进行收货
        receivingrcAsnDetailSN(params);

        result.put("detailId", detail.getId());
        result.put("asnId", detail.getAsnId());
        if (!"finishAll".equals(result.get(RfConstant.FORWARD_VALUE))) {
            result.put(RfConstant.FORWARD_VALUE, "newContainer");
            result.put(RfConstant.ENTITY_ID, asn.getId());
            result.put("container", "");
        }
        //result.put(RfConstant.CLEAR_VALUE, true);
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }



    /**
     * 收货确认
     *
     * @param params rf页面参数
     * @return rf页面参数
     * @throws RfBusinessException 作业人员为空时，提示 “未找到作业人员”
     * @throws RfBusinessException 收货数量小于0时，提示 “输入数量不能小于0”
     */
    @Override
    @Transactional
    public Map receivingrcAsnDetail(Map params){
        Map<String, Object> result = new HashMap<String, Object>();
        WmsWorker worker = WmsWorkerHolder.get();
        if (null == worker) {
            throw new RfBusinessException("not.found.worker");
        }
        Long workerId = worker.getId();
        Long detailId =
                params.get("detailId") == null ? null : Long.valueOf(params.get("detailId").toString());
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, detailId);
        detail.setOperateLog(detail.getOperateLog()+"\n"+"RF跨明细收货：");
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        WmsBillType billType = commonDao.load(WmsBillType.class, asn.getBillTypeId());

        Long unitId =
                params.get("asnDetail.unit") == null ? null : Long.valueOf(params.get("asnDetail.unit").toString());
        //String itemStateId = params.get("asnDetail.itemState") == null ? null : params.get("asnDetail.itemState").toString();
        String container = params.get("container") == null || params.get("container").toString().isEmpty() ? null
                : params.get("container").toString();
        String snCode = params.get("snCode") == null || params.get("snCode").toString().isEmpty() ? null
                : params.get("snCode").toString();
        Long receiveLocationId = Long.valueOf(params.get("receiveLocationId").toString());

        //扫码类型的值
        String scanCodeContainer = "";
        if(StringUtils.isNotEmpty(params.get("scanCodeContainer") == null ? "" : params.get("scanCodeContainer").toString())){
            scanCodeContainer = params.get("scanCodeContainer") + "," + params.get("scanCodeType") == null ? "" : params.get("scanCodeContainer").toString() + "," + params.get("scanCodeType");
        }

        WmsItem item = commonDao.load(WmsItem.class, detail.getItemId());

        Boolean itemBeReceivingSn = item.getBeReceivingSn();

        LotInfo lotInfo = wmsLotRuleManager.convertMapToLotInfoForReceive(item.getId(), params);

        String asnId = params.get("asnId") == null ? null : params.get("asnId").toString();

        String scanCodeHuOld = params.get("scanCodeHuOld") == null ? null : params.get("scanCodeHuOld").toString().substring(0,params.get("scanCodeHuOld").toString().length()-1);
        logger.info("选中的ASN明细id[" + detailId + "]扫描的所有HU：" + scanCodeHuOld);
        String [] huSplit = scanCodeHuOld.split("\\|");

        List huList = new ArrayList();
        Map<String, List<ZSWMSMSEG>> smsMap = new HashMap<>();
        Map<String, List<SmsVocherNo>> voucherMap = new HashMap<>();
        for(int i = 0; i < huSplit.length; i++){
            String [] scanHu = huSplit[i].split("\\^");
            logger.info("HU（" + scanHu[5] + "）,数量[" + scanHu[1] + "]在进行收货操作...");
            huList.add(scanHu[5]);
            WmsItem wmsItem = (WmsItem)this.commonDao.findByQueryUniqueResult("from WmsItem item where 1=1 and item.code = :itemCode and item.status = 'E' ", new String[]{"itemCode"}, new Object[]{scanHu[0]});
            Long factoryId;
            String detailHql;
            if(null == detail.getFactoryId()){
                detailHql = "select detail from WmsASNDetail detail left join WmsASN asn on asn.id = detail.asnId where 1 = 1 and detail.itemId = :itemId and detail.asnId = :asnId " +
                        "and asn.recFactoryId = :factoryId and detail.receivedQty < detail.expectedQty.baseQty ";
                factoryId = asn.getRecFactoryId();
            }else{
                detailHql = "from WmsASNDetail detail where 1 = 1 and detail.itemId = :itemId and detail.asnId = :asnId and detail.factoryId = :factoryId and detail.receivedQty < detail.expectedQty.baseQty ";
                factoryId = detail.getFactoryId();
            }
            List<WmsASNDetail> detailList = this.commonDao.findByQuery(detailHql, new String[]{"itemId", "asnId", "factoryId"}, new Object[]{Long.valueOf(wmsItem.getId()), Long.valueOf(asnId), factoryId});
            WmsPackageUnit packageUnit = (WmsPackageUnit)this.commonDao.findByQueryUniqueResult("from WmsPackageUnit packageUnit where 1=1 and packageUnit.itemId = :itemId and packageUnit.status = 'E' ",
                    new String[]{"itemId"}, new Object[]{wmsItem.getId()});
            Double qty = Double.valueOf(scanHu[1]);//剩余HU数量

            for(WmsASNDetail detail1 : detailList){
                StringBuffer sb = new StringBuffer();
                sb.append("from WmsInventoryState itemState where 1=1 and itemState.companyId = :companyId and itemState.status = 'E' ");
                if(WmsBillTypeCode.CGSH.equals(billType.getCode()) || WmsBillTypeCode.WXRKYZ.equals(billType.getCode())
                        || WmsBillTypeCode.MFJH.equals(billType.getCode()) || WmsBillTypeCode.FWLX.equals(billType.getCode())){
                    if(item.getBeWarehouseQua()){
                        sb.append("and itemState.name = '待检' ");
                        List<WmsInventoryState> inventoryStateList = this.commonDao.findByQuery(sb.toString(), new String[]{"companyId"}, new Object[]{asn.getCompanyId()});
                        if(inventoryStateList.size() == 0){
                            throw new RfBusinessException("[待检状态]在库存状态中未维护！");
                        }
                        if("待检".equals(inventoryStateList.get(0).getName())){
                            params.put("asnDetail.itemState", inventoryStateList.get(0).getId());
                        }
                    }else{
                        sb.append("and itemState.name = '合格' ");
                        List<WmsInventoryState> inventoryStateList = this.commonDao.findByQuery(sb.toString(), new String[]{"companyId"}, new Object[]{asn.getCompanyId()});
                        if(inventoryStateList.size() == 0){
                            throw new RfBusinessException("[合格状态]在库存状态中未维护！");
                        }
                        params.put("asnDetail.itemState", inventoryStateList.get(0).getId());
                    }
                }else{
                    sb.append("and itemState.name = '合格' ");
                    List<WmsInventoryState> inventoryStateList = this.commonDao.findByQuery(sb.toString(), new String[]{"companyId"}, new Object[]{asn.getCompanyId()});
                    if(inventoryStateList.size() == 0){
                        throw new RfBusinessException("[合格状态]在库存状态中未维护！");
                    }
                    if("合格".equals(inventoryStateList.get(0).getName())){
                        params.put("asnDetail.itemState", inventoryStateList.get(0).getId());
                    }
                }
                String itemStateId = params.get("asnDetail.itemState") == null ? null : params.get("asnDetail.itemState").toString();//库存状态
                String mapKey ="";
                if(billType.getCode().equals("CGSH") && !item.getBeWarehouseQua()) {
                    mapKey = asn.getCode()+"#101";
                }else if(billType.getCode().equals("CGSH")&& item.getBeWarehouseQua()){
                    mapKey = asn.getCode()+"#103";
                }else if(billType.getCode().equals("WXRKYZ")){
                    mapKey = asn.getCode()+"#542";
                }else if(billType.getCode().equals("DBASNSH")){
                    mapKey = asn.getCode()+"#311";
                }
                logger.info("单号：" + asn.getCode() + ",过账类型：" + mapKey + "数量["+ qty + "],asn明细id[" + detail.getId() + "]==========");
                List<ZSWMSMSEG> smsList =( smsMap.get(mapKey)==null ? new ArrayList<>():smsMap.get(mapKey));
                //跨明细收货计算数量
                if(qty <= (detail1.getExpectedQty().getBaseQty() - detail1.getReceivedQty())){
                    List<ZSWMSMSEG>  returnSms =   wmsASNManager.detailReceive(detail1, lotInfo, detail1.getExpectedQty().getConvertFigure(), packageUnit.getId(), qty, itemStateId, workerId, snCode,
                            receiveLocationId, container, scanHu[5], "", scanCodeContainer, scanHu[2], scanHu[4],scanHu[3],voucherMap);
                     smsList.addAll(returnSms);
			         smsMap.put(mapKey,smsList);
                    break;
                }else{
                    qty = qty - (detail1.getExpectedQty().getBaseQty() - detail1.getReceivedQty());
                    Double receivedQty = detail1.getExpectedQty().getBaseQty() - detail1.getReceivedQty();
                    List<ZSWMSMSEG>  returnSms =   wmsASNManager.detailReceive(detail1, lotInfo, detail1.getExpectedQty().getConvertFigure(), packageUnit.getId(), receivedQty, itemStateId, workerId, snCode,
                            receiveLocationId, container, scanHu[5], "", scanCodeContainer, scanHu[2], scanHu[4],scanHu[3],voucherMap);
                    smsList.addAll(returnSms);
                    smsMap.put(mapKey,smsList);
                }
            }
        }
        //过账接口外的接口逻辑
        //getInterface(scanHu, asn);

       logger.info("单号：" + asn.getCode() + ",单据类型：" + billType.getCode() + "开始过账..." + new Date());
       if (billType.getCode().equals("DBASNSH")) {
           wmsAsynManager.zwmsMIGOPOST311(smsMap, voucherMap);
       } else if (billType.getCode().equals("CGSH")/* || billType.getCode().equals("WXRKYZ")*/) {
           //过账101,103,542
           wmsAsynManager.zwmsMIGOPOST(smsMap, voucherMap);
       } else {
           //过账
           if (!"TSRKYZ".equals(billType.getCode()) && !"TSCKYZ".equals(billType.getCode()) && !"TSRKWZ".equals(billType.getCode()) && !"TSCKWZ".equals(billType.getCode())) {
               getReceipt(asn);
           }
       }
        logger.info("单号：" + asn.getCode() + ",单据类型：" + billType.getCode() + "结束过账..." + new Date());
        //创建上架作业任务
        WmsLocation wmsLocation = commonDao.load(WmsLocation.class, receiveLocationId);
        if(wmsLocation.getBeStayPut()){
            //是否待上架库位 为 是 则创建上架作业单，否 则不创建
            logger.info("asn单号:"+asn.getCode()+"普通收货createWmsMoveDocAndAllocate1自动创建上架作业单开始!!!!!");
            wmsASNManager.createWmsMoveDocAndAllocate1(asn, huList, WmsWorkerHolder.get().getId());
            logger.info("asn单号:"+asn.getCode()+"普通收货createWmsMoveDocAndAllocate1自动创建上架作业单结束!!!!!");
        }

        Object[] asnSumQty = (Object[]) this.commonDao.findByQueryUniqueResult(
                "select sum(detail.receivedQty),sum(detail.expectedQty.baseQty) from WmsASNDetail detail where detail"
                        + ".asnId = :asnId", "asnId", asn.getId());
        result.put(RfConstant.ENTITY_ID,detail.getId());
        result.put("asnId",detail.getAsnId());
        if (detail.getReceivedQty() < detail.getExpectedQty().getBaseQty()) {
            Double receivingPackQty =  DoubleUtils.div(detail.getReceivedQty(),detail.getExpectedQty().getConvertFigure(),detail.getExpectedQty().getBuPrecision());
            Double unReceivePackQty = DoubleUtils.sub(detail.getExpectedQty().getPackQty(),receivingPackQty,detail.getExpectedQty().getBuPrecision());
            if (itemBeReceivingSn){
                result.put("asnDetail.unReceiveQty", 1D);
                result.put("snCode", "");
            }else {
                result.put("asnDetail.unReceiveQty", unReceivePackQty);
            }
            result.put(RfConstant.FORWARD_VALUE, "finishPart");
        }
        if (detail.getReceivedQty() >= detail.getExpectedQty().getBaseQty()) {
            if (Double.parseDouble(asnSumQty[0].toString()) >= Double.parseDouble(asnSumQty[1].toString())) {
                result.put(RfConstant.FORWARD_VALUE, "finishAll");
                result.put(RfConstant.CLEAR_VALUE, "true");
            } else {
                result.put(RfConstant.FORWARD_VALUE, "finishItem");
            }
        }

        params.clear();
        return result;
    }



    //WMS通过接口将溯源信息推送到SAP
    private void zwmsSNIMPORT(WmsSnTraceability snTraceability){
        try{
            ThornUser user = UserHolder.getUser();
            //WMS通过接口将溯源信息推送到SAP
            ZWMS_SN_IMPORT_S[] SN_IMPORT_LIST=new ZWMS_SN_IMPORT_S[0];
            ZWMS_SN_IMPORT_S SN_IMPORT=new ZWMS_SN_IMPORT_S();
            SN_IMPORT.setZXH(snTraceability.getId().toString());//序号  必输，顺序号 从1开始递增，用与区分相同凭证行的不同序列号，方便提供报错消息
            SN_IMPORT.setMBLNR(snTraceability.getMaterialProof());//物料凭证号 必输
            SN_IMPORT.setMJAHR(null);//物料凭证年度  必输
            SN_IMPORT.setZEILE(snTraceability.getMaterialProofProject());//物料凭证行项目号  必输
            if (snTraceability.getItemId()!=null){
                WmsItem item = commonDao.load(WmsItem.class,snTraceability.getItemId());
                SN_IMPORT.setMATNR(item.getCode());//物料编码 必输
                if ("20".equals(item.getClass2())){
                    SN_IMPORT.setZPRODUCT_TYPE("芯片");//产品类型   20-芯片 30-探测器 40-机芯 50-整机
                }else if ("30".equals(item.getClass2())){
                    SN_IMPORT.setZPRODUCT_TYPE("探测器");
                }else if ("40".equals(item.getClass2())){
                    SN_IMPORT.setZPRODUCT_TYPE("机芯");
                }else if ("50".equals(item.getClass2())){
                    SN_IMPORT.setZPRODUCT_TYPE("整机");
                }
            }
            SN_IMPORT.setZSN(snTraceability.getSerialNo());//序列号  必输
            SN_IMPORT.setZLEVEL(snTraceability.getLevel());//序列号等级  （等级）
            if (snTraceability.getFactoryId()!=null){
                WmsErpWh erp = commonDao.load(WmsErpWh.class,snTraceability.getFactoryId());
                SN_IMPORT.setWERKS(erp.getCode());//工厂
                SN_IMPORT.setLGORT(erp.getInventoryPlace());//库存地点
            }
            if (snTraceability.getSupplierId()!=null){
                WmsSupplier sup = commonDao.load(WmsSupplier.class,snTraceability.getSupplierId());
                SN_IMPORT.setLIFNR(sup.getCode());//供应商编号
            }
            if (snTraceability.getAssemblyItemId() !=null){
                WmsItem assemblyItem = commonDao.load(WmsItem.class,snTraceability.getAssemblyItemId());
                SN_IMPORT.setMATNR_PRE(assemblyItem.getCode());//装配物料编码  101/309(探测器收货)入库必填，关联库存查询
            }
            SN_IMPORT.setZSN_PRE(snTraceability.getAssemblySerialNo());//装配序列号  101/309(探测器收货)入库必填，
            SN_IMPORT.setLGORT_PRE(snTraceability.getAssemblyPlace());//装配库存地点  101/309(探测器收货)入库必填，
            SN_IMPORT.setZBFBZ(snTraceability.getScrapRemarks());//报废备注
            SN_IMPORT.setBUDAT_MKPF(snTraceability.getWarehouseTime()==null?null:snTraceability.getWarehouseTime().toString());//入库时间  101/309(探测器收货)入库必填，311必填
            SN_IMPORT.setBWART(snTraceability.getMoveType());//移动类型  必输
            SN_IMPORT.setREJECT(snTraceability.getBeScrap()?"Y":"N");//是否报废  Y-是 N-否  (装配序列号是否报废)
            SN_IMPORT.setZUSER(user.getName());//处理人  必输，可为中文姓名
            SN_IMPORT.setZIMPORT_TYPE("01");//导入类型  固定值01

            ZWMS_PP_SN_IMPORTProxy z = new ZWMS_PP_SN_IMPORTProxy();
            ZWMS_SN_IMPORT_RETURN_S[] strReturns =z.ZWMS_PP_SN_IMPORT(SN_IMPORT_LIST);

            if(strReturns.length>0){
                ZWMS_SN_IMPORT_RETURN_S strReturn=strReturns[0];
                if(strReturn.getTYPE().equals("S")){

                }else{
                    throw new BusinessException(strReturn.getMESSAGE());
                }
            }
        }catch(Exception e){
            e.printStackTrace();
            throw new BusinessException(e.getMessage());
        }
    }

    /**
     * SN收货确认
     *
     * @param params rf页面参数
     * @return rf页面参数
     * @throws RfBusinessException 作业人员为空时，提示 “未找到作业人员”
     * @throws RfBusinessException 收货数量小于0时，提示 “输入数量不能小于0”
     */
    @Override
    @Transactional
    public Map receivingrcAsnDetailSN(Map params) {
            Map<String, Object> result = new HashMap<String, Object>();
            WmsWorker worker = WmsWorkerHolder.get();
            if (null == worker) {
                throw new RfBusinessException("not.found.worker");
            }
            Long workerId = worker.getId();
            Long detailId = params.get("detailId") == null ? null : Long.valueOf(params.get("detailId").toString());
            WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, detailId);
            WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
            WmsItem item = commonDao.load(WmsItem.class, detail.getItemId());
            WmsBillType billType = commonDao.load(WmsBillType.class, asn.getBillTypeId());
            String container = params.get("container") == null || params.get("container").toString().isEmpty() ? null
                    : params.get("container").toString();
            Long receiveLocationId = params.get("receiveLocationId") == null ? asn.getReceiveLocationId()
                    : Long.valueOf(params.get("receiveLocationId").toString());
            Boolean itemBeReceivingSn = item.getBeReceivingSn();
            LotInfo lotInfo = wmsLotRuleManager.convertMapToLotInfoForReceive(item.getId(), params);
            String pnAndSnAndLocaOld = params.get("pnAndSnAndLocaOld") == null ? null : params.get("pnAndSnAndLocaOld").toString().substring(0,params.get("pnAndSnAndLocaOld").toString().length()-1);
            String allHu=params.get("allHu")+"";//所有的HU
            String allHuArr[]=allHu.split(",");
            if(null != detail.getFactoryId()){
                lotInfo.setErpId(detail.getFactoryId());
            }else if (null!=asn.getRecFactoryId()){
                lotInfo.setErpId(asn.getRecFactoryId());
            }
            if (detail.getLotInfo().getSupplierId() !=null && detail.getLotInfo().getSupplierId() != 0){
                lotInfo.setSupplierId(detail.getLotInfo().getSupplierId());
            }else if (asn.getSupplierId()!=null){
                lotInfo.setSupplierId(asn.getSupplierId());
            }
            try {
                lotInfo.setProductDate(new Date());
            } catch (Exception e) {
                e.printStackTrace();
            }
            logger.info("itemKey开始处理时间：" + com.vtradex.wms.baseserver.utils.DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
            WmsItemKey itemKey = wmsItemManager.getItemKey(item, lotInfo, asn.getInventoryRelateCode(),detail.getItemId(),detail.getLotInfo().getSupplierId(), detail.getPurchaseProductNo(), detail.getPurchaseProductLineNo());
            logger.info("itemKey的值：" + itemKey.conversionLotHash() + ",itemKey结束处理时间：" + com.vtradex.wms.baseserver.utils.DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
            if(null != asn.getSupplierId()){
                WmsSupplier supplier=commonDao.load(WmsSupplier.class,asn.getSupplierId());
                if(null != supplier){
                    parseExpireDate(item.getId(),supplier.getCode(),itemKey);
                }
            }else if(null != asn.getRecFactoryId()){
                WmsSupplier supplier=commonDao.load(WmsSupplier.class,asn.getRecFactoryId());
                if(null != supplier){
                    parseExpireDate(item.getId(),supplier.getCode(),itemKey);
                }
            }
            String [] pnAndSnAndLocaSplit = pnAndSnAndLocaOld.split("\\|");
            //前面扫的是绑托的二维码，存起来后续收货完之后写进库存对应的MoveTool
            String moveToolqrCode = params.get("moveToolqrCode") == null ? "" : params.get("moveToolqrCode").toString();
            StringBuffer sb = new StringBuffer();
            sb.append("from WmsInventoryState itemState where 1=1 and itemState.companyId = :companyId and itemState.status = 'E' ");
            if(WmsBillTypeCode.CGSH.equals(billType.getCode()) || WmsBillTypeCode.WXRKWZ.equals(billType.getCode())
                    || WmsBillTypeCode.MFJH.equals(billType.getCode()) || WmsBillTypeCode.FWLX.equals(billType.getCode())){
                if(item.getBeWarehouseQua()){
                    sb.append("and itemState.name = '待检' ");
                    List<WmsInventoryState> inventoryStateList = this.commonDao.findByQuery(sb.toString(), new String[]{"companyId"}, new Object[]{asn.getCompanyId()});
                    if(inventoryStateList.size() == 0){
                        throw new RfBusinessException("[待检状态]在库存状态中未维护！");
                    }
                    if("待检".equals(inventoryStateList.get(0).getName())){
                        params.put("asnDetail.itemState", inventoryStateList.get(0).getId());
                    }
                }else{
                    sb.append("and itemState.name = '合格' ");
                    List<WmsInventoryState> inventoryStateList = this.commonDao.findByQuery(sb.toString(), new String[]{"companyId"}, new Object[]{asn.getCompanyId()});
                    if(inventoryStateList.size() == 0){
                        throw new RfBusinessException("[合格状态]在库存状态中未维护！");
                    }
                    params.put("asnDetail.itemState", inventoryStateList.get(0).getId());
                }
            }else{
                sb.append("and itemState.name = '合格' ");
                List<WmsInventoryState> inventoryStateList = this.commonDao.findByQuery(sb.toString(), new String[]{"companyId"}, new Object[]{asn.getCompanyId()});
                if(inventoryStateList.size() == 0){
                    throw new RfBusinessException("[合格状态]在库存状态中未维护！");
                }
                if("合格".equals(inventoryStateList.get(0).getName())){
                    params.put("asnDetail.itemState", inventoryStateList.get(0).getId());
                }
            }
            String itemStateId = params.get("asnDetail.itemState") == null ? null : params.get("asnDetail.itemState").toString();//库存状态
            WmsPackageUnit packageUnit = (WmsPackageUnit)this.commonDao.findByQueryUniqueResult("from WmsPackageUnit packageUnit where 1=1 and packageUnit.itemId = :itemId and packageUnit.status = 'E' ",
                    new String[]{"itemId"}, new Object[]{item.getId()});
            List snList = new ArrayList();
            List<Map<String, String>> mapList = new ArrayList<>();
            Map<String, List<ZSWMSMSEG>> smsMap = new HashMap<>();
            Map<String, List<SmsVocherNo>> voucherMap = new HashMap<>();
            String mapKey ="";
            if(billType.getCode().equals("CGSH") && !item.getBeWarehouseQua()) {
                mapKey = asn.getCode()+"#101";
            }else if(billType.getCode().equals("CGSH")&& item.getBeWarehouseQua()){
                mapKey = asn.getCode()+"#103";
            }else if(billType.getCode().equals("WXRKYZ")){
                mapKey = asn.getCode()+"#542";
            }else if(billType.getCode().equals("DBASNSH")){
                mapKey = asn.getCode()+"#311";
            }
        List<ZSWMSMSEG> smsList =( smsMap.get(mapKey)==null ? new ArrayList<>():smsMap.get(mapKey));
        for (int i = 0; i < pnAndSnAndLocaSplit.length; i++) {
            String[] pnAndSnAndLocaSplits = pnAndSnAndLocaSplit[i].split("\\,");
            snList.add(pnAndSnAndLocaSplits[1]);
            int compare = DoubleUtils.compareByPrecision(detail.getExpectedQty().getBaseQty(), detail.getReceivedQty(), 2);
            if(compare != 0){
                String moveToolBoxNo = "";
                Map<String, String> map = new HashMap<>();
                if(pnAndSnAndLocaSplits.length == 3){
                    map.put("pn", pnAndSnAndLocaSplits[0]);
                    map.put("sn", pnAndSnAndLocaSplits[1]);
                    map.put("hu", pnAndSnAndLocaSplits[2]);
                    if(!pnAndSnAndLocaSplits[2].contains(" ")){
                        moveToolBoxNo = pnAndSnAndLocaSplits[2];
                    }
                }else{
                    map.put("pn", pnAndSnAndLocaSplits[0]);
                    map.put("sn", pnAndSnAndLocaSplits[1]);
                }

                logger.info("第"+i+",sn["+pnAndSnAndLocaSplits[1]+"]处理的开始时间：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
                List<ZSWMSMSEG>  returnSms = wmsASNManager.detailReceive(detail, lotInfo, detail.getExpectedQty().getConvertFigure(), packageUnit.getId(), Double.valueOf(1), itemStateId, workerId, pnAndSnAndLocaSplits[1],
                        receiveLocationId, container, moveToolBoxNo, pnAndSnAndLocaSplits[0], voucherMap, itemKey);
                logger.info("第"+i+",sn["+pnAndSnAndLocaSplits[1]+"]处理的结束时间：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
                if("JYFH".equals(billType.getCode())){//还料单据类型,扣除GYSKW上应的库存
                    List<WmsInventory> inventoryList=checkPicktictet(detail,pnAndSnAndLocaSplits[0],pnAndSnAndLocaSplits[1]);
                    if(null!=inventoryList&&inventoryList.size()>0){
                        logger.info("扣除供应商库存的开始时间：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
                        pickWmsInventory(inventoryList,asn,pnAndSnAndLocaSplits[1],pnAndSnAndLocaSplits[0]);
                        logger.info("扣除供应商库存的结束时间：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
                    }
                }
                mapList.add(map);
                smsList.addAll(returnSms);
                smsMap.put(mapKey,smsList);
            }else{
                break;
            }
        }
        //过账
        logger.info("单号：" + asn.getCode() + ",单据类型：" + billType.getCode() + "开始过账..." + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
        if(billType.getCode().equals("DBASNSH")){
            wmsAsynManager.zwmsMIGOPOSTSN311(smsMap,voucherMap, snList);
        }else if(billType.getCode().equals("CGSH") || billType.getCode().equals("WXRKYZ")) {
            //过账101,103,542
            wmsAsynManager.zwmsMIGOPOST(smsMap, voucherMap);
        }else{
            //过账
            if(!"TSRKYZ".equals(billType.getCode()) && !"TSCKYZ".equals(billType.getCode()) && !"TSRKWZ".equals(billType.getCode()) &&!"TSCKWZ".equals(billType.getCode())) {
                this.getReceiptSn(asn, snList);
            }
        }
        logger.info("单号：" + asn.getCode() + ",单据类型：" + billType.getCode() + "结束过账..." + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));

        if(WmsBillTypeCode.GDSH.equals(billType.getCode()) || WmsBillTypeCode.TCQGDSH.equals(billType.getCode())){
            //过账成功后通知SFC
            List<WmsReceivedRecord> wmsReceivedRecordList = new ArrayList<>();
            // 2、如果HU来源于SFC，收货成功后接口通知SFC；
            //判断扫的HU码是WMS还是SFC提供(首字符为数字则为WMS，非数字则为SFC)
            Pattern pattern = Pattern.compile("-?[0-9]+\\.?[0-9]*");
            //if(StringUtils.isNotEmpty(moveToolqrCode)){
            for(Map<String, String> m : mapList) {
                if(StringUtils.isNotEmpty(m.get("hu"))){
                    if(!m.get("hu").contains(" ")){
                        boolean strResult = pattern.matcher(m.get("hu").substring(0, 1)).matches();
                        if (!strResult) {
                            //收货成功后，通知SFC
                            //C005
                            WmsReceivedRecord wmsReceivedRecord = new WmsReceivedRecord();
                            wmsReceivedRecord.getMoveTool().setBoxNo(m.get("hu"));
                            wmsReceivedRecord.setPn(m.get("pn"));
                            wmsReceivedRecord.setSn(m.get("sn"));
                            wmsReceivedRecordList.add(wmsReceivedRecord);
                        }
                    }
                }
            }
            if(wmsReceivedRecordList.size() != 0){
                logger.info("过账成功后开始告知SFC：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
                wmsASNManager.saveCartonSuccess(wmsReceivedRecordList);
                logger.info("过账成功后告知SFC结束：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
            }
        }

        //探测器工单收货进行二次过账
        //1、通过HU发SFC接口获取信息
        //2、转换物料(收货记录、库存)
        //3、进行二次过账(309)
        if("TCQGDSH".equals(billType.getCode())){
            logger.info("单号：" + asn.getCode() + ",单据类型：" + billType.getCode() + "开始二次(换料号)过账..." + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
            getSecondaryReceipt(pnAndSnAndLocaSplit, moveToolqrCode, asn, item);
            logger.info("单号：" + asn.getCode() + ",单据类型：" + billType.getCode() + "结束二次(换料号)过账..." + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
        }

        //创建上架单
        logger.info("创建上架作业单开始：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
        WmsLocation wmsLocation = commonDao.load(WmsLocation.class, receiveLocationId);
        if(wmsLocation.getBeStayPut()){
            //是否待上架库位 为 是 则创建上架作业单，否 则不创建
            //wmsASNManager.createWmsMoveDocAndAllocate1(asn, snList, WmsWorkerHolder.get().getId());
            logger.info("asn单号:"+asn.getCode()+"普通收货createWmsMoveDocAndAllocate2自动创建上架作业单开始!!!!!");
            wmsASNManager.createWmsMoveDocAndAllocate2(asn, snList, WmsWorkerHolder.get().getId());
            logger.info("asn单号:"+asn.getCode()+"普通收货createWmsMoveDocAndAllocate2自动创建上架作业单结束!!!!!");
        }
        logger.info("创建上架作业单结束：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));

        Object[] asnSumQty = (Object[]) this.commonDao.findByQueryUniqueResult(
                "select sum(detail.receivedQty),sum(detail.expectedQty.baseQty) from WmsASNDetail detail where detail"
                        + ".asnId = :asnId", "asnId", asn.getId());
        result.put("detailId",detail.getId());
        result.put("asnId",detail.getAsnId());
        if (detail.getReceivedQty() < detail.getExpectedQty().getBaseQty()) {
            Double unReceiveQty = DoubleUtils.sub(detail.getExpectedQty().getBaseQty(), detail.getReceivedQty(),
                    detail.getExpectedQty().getBuPrecision());
            Double receivingPackQty =  DoubleUtils.div(detail.getReceivedQty(),detail.getExpectedQty().getConvertFigure(),detail.getExpectedQty().getBuPrecision());
            Double unReceivePackQty = DoubleUtils.sub(detail.getExpectedQty().getPackQty(),receivingPackQty,detail.getExpectedQty().getBuPrecision());
            if (itemBeReceivingSn){
                result.put("asnDetail.unReceiveQty", 1D);
                result.put("snCode", "");
            }else {
                result.put("asnDetail.unReceiveQty", unReceivePackQty);
            }
            result.put(RfConstant.FORWARD_VALUE, "finishPart");
        }
        if (detail.getReceivedQty() >= detail.getExpectedQty().getBaseQty()) {
            if (Double.parseDouble(asnSumQty[0].toString()) >= Double.parseDouble(asnSumQty[1].toString())) {
                result.put(RfConstant.FORWARD_VALUE, "finishAll");
                result.put(RfConstant.CLEAR_VALUE, "true");
            } else {
                result.put(RfConstant.FORWARD_VALUE, "finishItem");
            }
        }
        params.clear();
        return result;
    }

    /**
     * 因为只要扣个库存，所以只要一定循环，就跳出来
     * @param list
     * @param wmsASN
     * @param sn
     * @param pn
     */
    private void pickWmsInventory(List<WmsInventory> list,WmsASN wmsASN,String sn,String pn){
        if(null!=list&&list.size()>0){
            for(int j=0;j<list.size();j++){
                WmsInventory wmsInventory=list.get(j);
                if(wmsInventory.getQty().getBaseQty().doubleValue()>=1d){
                    InventoryQty inventoryQty=wmsInventory.getQty();
                    InventoryQty inventoryQty_start=new InventoryQty();
                    BeanUtils.copyEntity(inventoryQty_start, inventoryQty);

                    inventoryQty.setBaseQty(inventoryQty.getBaseQty()-1);
                    inventoryQty.setPackQty(WmsPackageUnitUtils.getPackQty(inventoryQty.getConvertFigure(), inventoryQty.getBaseQty(),
                            commonDao.load(WmsItem.class, wmsInventory.getSku().getItemId()).getBuPrecision()));
                    InventoryQty inventoryQty_end=new InventoryQty();
                    BeanUtils.copyEntity(inventoryQty_end, inventoryQty);

                    this.commonDao.store(wmsInventory);
                    WmsInventoryLog inventoryLog = new WmsInventoryLog();
                    inventoryLog.setStartQty(inventoryQty_start);
                    inventoryLog.setEndQty(inventoryQty_end);
                    inventoryLog.setSn(sn);
                    inventoryLog.setPalletNo(pn);
                    inventoryLog.setRelatedBillCode(wmsASN.getCode());
                    inventoryLog.setCompanyId(wmsASN.getCompanyId());
                    inventoryLog.setLocationId(wmsInventory.getLocationId());
                    inventoryLog.setSku(wmsInventory.getSku());
                    inventoryLog.setOutBoxNo(wmsInventory.getMoveTool().getOutBoxNo());
                    inventoryLog.setHu(wmsInventory.getMoveTool().getPalletNo());
                    this.commonDao.store(inventoryLog);
                    break;
                }
            }
        }
    }


    @Override
    @Transactional
    public Map createQualityInspection(Map params) {
        Map<String, Object> result = new HashMap<String, Object>();
        String ids = params.get("ids") == null ? "" : params.get("ids").toString();
        System.out.println(ids);
        String[] records = ids.split(",");
        List<Long> recordList = new ArrayList<>();
        for(String record : records){
            recordList.add(Long.valueOf(record));
        }

        List<Long> palletNoList = new ArrayList<>();//按pallet分组
        List<Long> outBoxNoList = new ArrayList<>();//按outBox分组
        List<Long> innerBoxNoList = new ArrayList<>();//按innerBox分组
        List<Long> reduce = new ArrayList<>(); //差值list
        List<Long> boxNoList = new ArrayList<>();
        List<Long> idd = new ArrayList<>();
        palletNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' and record.moveTool.palletNo is not null ", "recordId", recordList);
        List<Long> finalPalletNoList = palletNoList;
        if(palletNoList.size() == 0){
            outBoxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' and record.moveTool.outBoxNo is not null ", "recordId", recordList);
            if(outBoxNoList.size() == 0){
                innerBoxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' and record.moveTool.innerBoxNo is not null ", "recordId", recordList);
                if(innerBoxNoList.size() == 0){
                    boxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' ", "recordId", recordList);
                }
            }else {
                List<Long> finalOutBoxNoList1 = outBoxNoList;
                reduce = recordList.stream().filter(item -> !finalOutBoxNoList1.contains(item)).collect(Collectors.toList());
                if(reduce.size() != 0){
                    innerBoxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' and record.moveTool.innerBoxNo is not null ", "recordId", reduce);
                    if (innerBoxNoList.size() == 0){
                        boxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' ", "recordId", reduce);
                    }else {
                        List<Long> finalInnerBoxNoList2 = innerBoxNoList;
                        reduce = recordList.stream().filter(item -> !finalInnerBoxNoList2.contains(item)).collect(Collectors.toList());
                        boxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' ", "recordId", reduce);
                    }
                }
            }
        }else {
            reduce = recordList.stream().filter(item -> !finalPalletNoList.contains(item)).collect(Collectors.toList());
            if(reduce.size() != 0){
                outBoxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' and record.moveTool.outBoxNo is not null ", "recordId", reduce);
                if (outBoxNoList.size() == 0){
                    innerBoxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' and record.moveTool.innerBoxNo is not null ", "recordId", reduce);
                    if (innerBoxNoList.size() == 0){
                        boxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' ", "recordId", reduce);
                    }else {
                        List<Long> finalInnerBoxNoList1 = innerBoxNoList;
                        reduce = reduce.stream().filter(item -> !finalInnerBoxNoList1.contains(item)).collect(Collectors.toList());
                        boxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' ", "recordId", reduce);
                    }
                }else {
                    List<Long> finalOutBoxNoList = outBoxNoList;
                    reduce = reduce.stream().filter(item -> !finalOutBoxNoList.contains(item)).collect(Collectors.toList());
                    if(reduce.size() != 0){
                        innerBoxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' and record.moveTool.innerBoxNo is not null ", "recordId", reduce);
                        if (innerBoxNoList.size() == 0){
                            boxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' ", "recordId", reduce);
                        }else {
                            List<Long> finalInnerBoxNoList = innerBoxNoList;
                            reduce = reduce.stream().filter(item -> !finalInnerBoxNoList.contains(item)).collect(Collectors.toList());
                            boxNoList = commonDao.findByQuery("select record.id from WmsReceivedRecord record where record.id in (:recordId) and record.status = 'E' ", "recordId", reduce);
                        }
                    }
                }
            }
        }
        if (palletNoList.size()>0){
            createWmsQualityInspection(palletNoList);
        }
        if (outBoxNoList.size()>0){
            createWmsQualityInspection(outBoxNoList);
        }
        if (innerBoxNoList.size()>0){
            createWmsQualityInspection(innerBoxNoList);
        }
        if (boxNoList.size()>0){
            createWmsQualityInspection(boxNoList);
        }
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put(RfConstant.CLEAR_VALUE, Boolean.TRUE);
        return result;
    }

    public void createWmsQualityInspection(List<Long> palletNoList){
        Long id = palletNoList.get(0);
        WmsReceivedRecord oneRr = commonDao.load(WmsReceivedRecord.class,id);
        WmsQualityInspection quality = new WmsQualityInspection();
        //质检单号 生成规则：ZJD+当前日期+五位流水
        //日期（6位，210831)
        SimpleDateFormat sf = new SimpleDateFormat("yyyyMMdd");
        String qualityDate = sf.format(new Date());
        //流水码（5位数字）
        String sequenceNo = getRandom(5, (short)'0', (short)'9');
        String qualityCode = "ZJD" + qualityDate + sequenceNo ;
        quality.setQualityCode(qualityCode);
        quality.setInspectionSheetType("LL");
        quality.setInspectionAreaId(oneRr.getLocationId());
        this.commonDao.store(quality);
        for(Long rrId : palletNoList){
            WmsQualityInspectionDetail qualityDetail = new WmsQualityInspectionDetail();
            WmsReceivedRecord rr = commonDao.load(WmsReceivedRecord.class,rrId);
            WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, rr.getAsnDetailId());
            WmsASN asn = commonDao.load(WmsASN.class,detail.getAsnId());
            WmsItem item = commonDao.load(WmsItem.class, rr.getSku().getItemId());
            WmsErpWh wmsErpWh = EntityFactory.getEntity(WmsErpWh.class);
            if(null != detail.getFactoryId()){
                 wmsErpWh = commonDao.load(WmsErpWh.class, detail.getFactoryId());
            }else{
                wmsErpWh = commonDao.load(WmsErpWh.class, asn.getRecFactoryId());
            }

            qualityDetail.setRelatedBill(asn.getCode());
            qualityDetail.setQualityId(quality.getId());
            qualityDetail.setFactoryId(wmsErpWh.getId());
            qualityDetail.setStockPlace(wmsErpWh.getInventoryPlace());
            qualityDetail.setItemId(rr.getSku().getItemId());
            qualityDetail.setPlanQty(detail.getExpectedQty().getBaseQty());
            WmsItemKey itemKey = commonDao.load(WmsItemKey.class, rr.getSku().getItemKeyId());
            if(null != itemKey){
                qualityDetail.setSupplierId(itemKey.getLotInfo().getSupplierId());
            }
            qualityDetail.setPlanQty(rr.getQty().getBaseQty());
            qualityDetail.setLocationId(rr.getLocationId());
            qualityDetail.setQcLocationId(rr.getLocationId());
            qualityDetail.setHu(rr.getMoveTool().getBoxNo());
            qualityDetail.setPalletNo(rr.getMoveTool().getPalletNo());
            qualityDetail.setOutBoxNo(rr.getMoveTool().getOutBoxNo());
            qualityDetail.setInnerBoxNo(rr.getMoveTool().getInnerBoxNo());
            qualityDetail.setInspectionType(wmsErpWh.getAttribute());
            qualityDetail.setBeUrgent(detail.getUrgentState());

            StringBuffer sb1 = new StringBuffer();
            WmsSupplier supplier = commonDao.load(WmsSupplier.class, itemKey.getLotInfo().getSupplierId());
            if(null == supplier){
                throw new RfBusinessException("供应商为空！");
            }
            String hql = "from WmsInspectionRecord record where record.supplierId = :supplierId and record.itemId = :itemId ";
            sb1.append("select sample from WmsCycleSample sample left join WmsSupplier supplier on supplier.id = sample.cycleSupplierId where supplier.code = :supplierCode and sample.cycleItemId = :itemId and sample.status = 'E' ");
            List<WmsInspectionRecord> recordList = new ArrayList<>();
            WmsCycleSample sample = (WmsCycleSample) commonDao.findByQueryUniqueResult(sb1.toString(), new String[]{"supplierCode", "itemId"}, new Object[]{supplier.getCode(), item.getId()});
            if(item.getBeROHS()){
                StringBuffer sb = new StringBuffer();
                sb.append(hql);
                sb.append("order by record.rohsDate desc");
                recordList = commonDao.findByQuery(sb.toString(), new String[]{"supplierId", "itemId"}, new Object[]{itemKey.getLotInfo().getSupplierId(), item.getId()});
                if(recordList.size() == 0){
                    //throw new RfBusinessException("未找到上次的ROHS时间！");
                    qualityDetail.setBeROHS(Boolean.TRUE);
                }else{
                    //计算rohs的时间
                    if(null == recordList.get(0).getRohsDate()){
                        qualityDetail.setBeROHS(Boolean.TRUE);
                    }else{
                        Date date = DateUtil.addHourToDate(recordList.get(0).getRohsDate(), Double.valueOf(sample.getRohsCycle() * 24));
                        int res = date.compareTo(rr.getUpdateInfo().getCreatedTime());//相等则返回0，date大返回1，否则返回-1
                        if(res == 1 || res == 0){
                            qualityDetail.setBeROHS(Boolean.FALSE);
                        }else{
                            qualityDetail.setBeROHS(Boolean.TRUE);
                        }
                    }
                }
            }
            if(item.getBeORT()){
                StringBuffer sb = new StringBuffer();
                sb.append(hql);
                sb.append("order by record.ortDate desc");
                recordList = commonDao.findByQuery(sb.toString(), new String[]{"supplierId", "itemId"}, new Object[]{itemKey.getLotInfo().getSupplierId(), item.getId()});
                if(recordList.size() == 0){
                    //throw new RfBusinessException("未找到上次的ORT时间！");
                    qualityDetail.setBeORT(Boolean.TRUE);
                }else{
                    //计算ort的时间
                    if(null == recordList.get(0).getOrtDate()){
                        qualityDetail.setBeORT(Boolean.TRUE);
                    }else{
                        Date date = DateUtil.addHourToDate(recordList.get(0).getOrtDate(), Double.valueOf(sample.getOrtCycle() * 24));
                        int res = date.compareTo(rr.getUpdateInfo().getCreatedTime());//相等则返回0，date大返回1，否则返回-1
                        if(res == 1 || res == 0){
                            qualityDetail.setBeORT(Boolean.FALSE);
                        }else{
                            qualityDetail.setBeORT(Boolean.TRUE);
                        }
                    }
                }
            }
            if(item.getBeESD()){
                StringBuffer sb = new StringBuffer();
                sb.append(hql);
                sb.append("order by record.esdDate desc");
                recordList = commonDao.findByQuery(sb.toString(), new String[]{"supplierId", "itemId"}, new Object[]{itemKey.getLotInfo().getSupplierId(), item.getId()});
                if(recordList.size() == 0){
                    //throw new RfBusinessException("未找到上次的ESD时间！");
                    qualityDetail.setBeESD(Boolean.TRUE);
                }else{
                    //计算esd的时间
                    if(null == recordList.get(0).getEsdDate()){
                        qualityDetail.setBeESD(Boolean.TRUE);
                    }else{
                        Date date = DateUtil.addHourToDate(recordList.get(0).getEsdDate(), Double.valueOf(sample.getEsdCycle() * 24));
                        int res = date.compareTo(rr.getUpdateInfo().getCreatedTime());//相等则返回0，date大返回1，否则返回-1
                        if(res == 1 || res == 0){
                            qualityDetail.setBeESD(Boolean.FALSE);
                        }else{
                            qualityDetail.setBeESD(Boolean.TRUE);
                        }
                    }
                }
            }
            this.commonDao.store(qualityDetail);
            logger.info(rr.getId() + "-" + rr.getCode() + ",生成质检单成功！");
            rr.setFlag("1");
            commonDao.store(rr);
        }

    }


    private static String getRandom(int length, short start, short end) {
        char[] charArray = new char[length];
        //short start = (short)'a';   //0的ASCII码是48
        //short end = (short)'z';    //z的ASCII码是122（0到z之间有特殊字符）
        for (int i = 0; i < length; i++) {
            while(true)
            {
                char cc1 = (char)((Math.random()*(end-start))+start);
                if(Character.isLetterOrDigit(cc1))  //判断字符是否是数字或者字母
                {
                    charArray[i] = cc1;
                    break;
                }
            }
        }
        String StringRes = new String(charArray);//把字符数组转化为字符串
        return StringRes;
    }


    /*public void postingMethod(WmsASN asn, WmsASNDetail detail, double quantity, String snCode, String moveToolBoxNo, String resp, String pnCode){
        //过账成功后，凭证记录写入凭证号管理中
        WmsVoucherNo wmsVoucherNo = new WmsVoucherNo();
        List<WmsSnBindPn> wmsSnBindPnList = new ArrayList<>();
        if(StringUtils.isEmpty(snCode)){
            wmsSnBindPnList = commonDao.findByQuery("from WmsSnBindPn bind where bind.hu = :hu ", "hu", moveToolBoxNo);
        }else{
            wmsSnBindPnList = commonDao.findByQuery("from WmsSnBindPn bind where bind.sn = :sn and bind.pn = :pn ", new String[]{"sn", "pn"}, new Object[]{snCode, pnCode});
        }

        WmsBillType billType = this.commonDao.load(WmsBillType.class, asn.getBillTypeId());
        WmsItem wmsItem = this.commonDao.load(WmsItem.class, detail.getItemId());

        if(wmsSnBindPnList.size() == 0){
            JSONObject jsonObject = JSONObject.parseObject(resp);
            if("CGSH".equals(billType)){
                if(jsonObject.get("code").toString().equals("E")){
                    JSONArray listObject = jsonObject.getJSONArray("data");
                    if(listObject!=null&&!listObject.isEmpty()){
                        for (int i = 0; i < listObject.size(); i++) {
                            wmsVoucherNo.setRelatedBillCode(asn.getCode());//asn单号
                            wmsVoucherNo.setItemId(wmsItem.getId());//货品id
                            if(StringUtils.isEmpty(snCode)){
                                wmsVoucherNo.setHu(moveToolBoxNo);//hu
                            }else{
                                wmsVoucherNo.setHu(wmsSnBindPnList.get(0).getHu());
                            }

                            if("CGSH".equals(billType.getCode())){
                                if(wmsItem.getBeWarehouseQua()){
                                    wmsVoucherNo.setMoveType("103");
                                }else if(!wmsItem.getBeWarehouseQua()){
                                    wmsVoucherNo.setMoveType("101");
                                }
                            }else{
                                wmsVoucherNo.setMoveType("103");
                            }
                            commonDao.store(wmsVoucherNo);
                        }
                    }
                }else if(jsonObject.get("code").toString().equals("S")){
                    JSONArray listObject = jsonObject.getJSONArray("data");
                    JSONObject[] js = new JSONObject[listObject.size()];
                    if(listObject!=null&&!listObject.isEmpty()){
                        for (int i = 0; i < listObject.size(); i++) {
                            js[i] = listObject.getJSONObject(i);
                            wmsVoucherNo.setRelatedBillCode(asn.getCode());//asn单号
                            wmsVoucherNo.setItemId(wmsItem.getId());//货品id
                            if(StringUtils.isEmpty(snCode)){
                                wmsVoucherNo.setHu(moveToolBoxNo);//hu
                            }else{
                                wmsVoucherNo.setHu(wmsSnBindPnList.get(0).getHu());
                            }
                            if("CGSH".equals(billType)){
                                if(wmsItem.getBeWarehouseQua()){
                                    wmsVoucherNo.setMoveType("103");
                                }else if(!wmsItem.getBeWarehouseQua()){
                                    wmsVoucherNo.setMoveType("101");
                                }
                            }else{
                                wmsVoucherNo.setMoveType("103");
                            }
                            wmsVoucherNo.setPostVoucherQty(quantity);
                            wmsVoucherNo.setPostVoucher(js[i].get("MBLNR").toString());
                            wmsVoucherNo.setPostVoucherProject(js[i].get("ZEILE").toString());
                            wmsVoucherNo.setPostVoucherYear(js[i].get("MJAHR").toString());//物料凭证号年份
                            wmsVoucherNo.setPostStatus("S");//过账状态
                            wmsVoucherNo.setMessage(js[i].get("MESSAGE").toString());//过账信息
                            commonDao.store(wmsVoucherNo);
                        }
                    }
                }
            }else{
                if(jsonObject.get("code").toString().equals("E")){
                    JSONArray listObject = jsonObject.getJSONArray("data");
                    if(listObject!=null&&!listObject.isEmpty()){
                        for (int i = 0; i < listObject.size(); i++) {
                            String message=listObject.getJSONObject(i).get("MESSAGE").toString();
                            throw new BusinessException(message);
                        }
                    }
                }else if(jsonObject.get("code").toString().equals("S")){
                    JSONArray listObject = jsonObject.getJSONArray("data");
                    JSONObject[] js = new JSONObject[listObject.size()];
                    if(listObject!=null&&!listObject.isEmpty()){
                        for (int i = 0; i < listObject.size(); i++) {
                            js[i] = listObject.getJSONObject(i);
                            wmsVoucherNo.setRelatedBillCode(asn.getCode());//asn单号
                            wmsVoucherNo.setItemId(wmsItem.getId());//货品id
                            if(StringUtils.isEmpty(snCode)){
                                wmsVoucherNo.setHu(moveToolBoxNo);//hu
                            }else{
                                wmsVoucherNo.setHu(wmsSnBindPnList.get(0).getHu());
                            }
                            if("CGSH".equals(billType.getCode())){
                                if(wmsItem.getBeWarehouseQua()){
                                    wmsVoucherNo.setMoveType("103");
                                }else if(!wmsItem.getBeWarehouseQua()){
                                    wmsVoucherNo.setMoveType("101");
                                }
                            }else{
                                wmsVoucherNo.setMoveType("103");
                            }
                            wmsVoucherNo.setPostVoucherQty(quantity);
                            wmsVoucherNo.setPostVoucher(js[i].get("MBLNR").toString());
                            wmsVoucherNo.setPostVoucherProject(js[i].get("ZEILE").toString());
                            wmsVoucherNo.setPostVoucherYear(js[i].get("MJAHR").toString());//物料凭证号年份
                            wmsVoucherNo.setPostStatus("S");//过账状态
                            wmsVoucherNo.setMessage(js[i].get("MESSAGE").toString());//过账信息
                            commonDao.store(wmsVoucherNo);
                        }
                    }
                }
            }
        }
    }*/

    /**
     * 收货接口逻辑
     */
    private void getReceipt(WmsASN asn){
        wmsAsynManager.zwmsMIGOPOST(asn.getId(),null,false, null);
        //收货完之后进行过账
        //postingMethod(asn, detail, Double.valueOf(scanHu[1]), snCode, scanHu[2], resp);
    }

    private void getReceiptSn(WmsASN asn, List snList){
        wmsAsynManager.zwmsMIGOPOST(asn.getId(),null,true, snList);
        //收货完之后进行过账
        //postingMethod(asn, detail, Double.valueOf(scanHu[1]), snCode, scanHu[2], resp);
    }

    /**
     * 探测器工单收货进行二次过账
     * 1、通过HU发SFC接口获取信息
     * 2、转换物料(收货记录、库存)
     * 3、进行二次过账(309)
     * */
    public void getSecondaryReceipt(String[] pnAndSn, String huCode, WmsASN asn, WmsItem wmsItem){
        if(!wmsItem.getCode().equals(pnAndSn[0].split("\\,")[0])){
            for(int i = 0; i < pnAndSn.length; i++){
                String pnAndSns = pnAndSn[i];
                String [] pnAndSnss = pnAndSns.split("\\,");
                String hu = pnAndSnss[2];
                WmsPnSup pnSup = new WmsPnSup();
                String findPn = "SELECT ps FROM WmsPnSup ps where ps.pn = :pn";
                if(StringUtils.isNotEmpty(hu)){
                    pnSup = (WmsPnSup) commonDao.findByQueryUniqueResult(findPn,new String[]{"pn"},new Object[]{pnAndSnss[0]});
                    List<WmsReceivedRecord> recordList = this.commonDao.findByQuery("from WmsReceivedRecord record where record.moveTool.boxNo = :hu and record.status = 'E' ", "hu", hu);
                    WmsItem item = EntityFactory.getEntity(WmsItem.class);
                    if(null == pnSup){
                        item = (WmsItem)this.commonDao.findByQueryUniqueResult("from WmsItem item where item.code = :itemCode and item.status = 'E' ", "itemCode", pnAndSnss[0]);
                    }else{
                        item = (WmsItem)this.commonDao.findByQueryUniqueResult("from WmsItem item where item.id = :itemCode and item.status = 'E' ", "itemCode", pnSup.getItemId());
                    }

                    for(WmsReceivedRecord record : recordList){
                        record.getSku().setItemId(item.getId());
                        commonDao.store(record);
                    }

                    if(hu.contains(" ")){
                        String [] hus = hu.split("\\ ");
                        String pn = hus[0];
                        String sn = hus[1];
                        String hql = "select inventory from WmsInventory inventory left join WmsBox box on box.inventoryId = inventory.id where inventory.pn = :pn and box.snNumber = :snNumber and box.status != 'S' ";
                        WmsInventory inventory = (WmsInventory)commonDao.findByQueryUniqueResult(hql, new String[]{"pn", "snNumber"}, new Object[]{pn, sn});
                        inventory.getSku().setItemId(item.getId());
                        commonDao.store(inventory);
                        //修改唯一码明细的货品ID
                        String hql1 = "select content from WmsBoxContent content left join WmsBox box on box.id = content.boxId left join WmsInventory inventory on inventory.id = box.inventoryId " +
                                "where inventory.pn = :pn and box.snNumber = :snNumber and box.status != 'S' ";
                        List<WmsBoxContent> contentList = commonDao.findByQuery(hql1, new String[]{"pn", "snNumber"}, new Object[]{pn, sn});
                        WmsItem finalItem = item;
                        contentList.forEach(f -> {
                            f.setItemId(finalItem.getId());
                            commonDao.store(f);
                        });
                    }else{
                        WmsItem finalItem = item;
                        List<WmsInventory> inventoryList = commonDao.findByQuery("from WmsInventory inventory where inventory.moveTool.boxNo = :hu ", "hu", hu);
                        //inventoryList.get(0).getSku().setItemId(item.getId());
                        inventoryList.forEach(f -> {
                            f.getSku().setItemId(finalItem.getId());
                            commonDao.store(f);
                        });
                        String hql1 = "select content from WmsBoxContent content left join WmsBox box on box.id = content.boxId left join WmsInventory inventory on inventory.id = box.inventoryId " +
                                "where inventory.moveTool.boxNo = :hu ";
                        List<WmsBoxContent> contentList = commonDao.findByQuery(hql1, "hu", hu);
                        contentList.forEach(f -> {
                            f.setItemId(finalItem.getId());
                            commonDao.store(f);
                        });
                    }
                }else{
                    pnSup = (WmsPnSup) commonDao.findByQueryUniqueResult(findPn,new String[]{"pn"},new Object[]{pnAndSnss[0]});
                    WmsItem item = EntityFactory.getEntity(WmsItem.class);
                    if(null == pnSup){
                        item = (WmsItem)this.commonDao.findByQueryUniqueResult("from WmsItem item where item.code = :itemCode and item.status = 'E' ", "itemCode", pnAndSnss[0]);//pn就是料号
                    }else{
                        item = (WmsItem)this.commonDao.findByQueryUniqueResult("from WmsItem item where item.id = :itemCode and item.status = 'E' ", "itemCode", pnSup.getItemId());
                    }
                    String findrr = "select rr from WmsReceivedRecord rr left join WmsASNDetail d on d.id = rr.asnDetailId " +
                            "left join WmsASN asn on asn.id = d.asnId where asn.id = :asnId and rr.sku.itemId = :itemId and rr.receivePostStatus = 'NP' ";
                    List<WmsReceivedRecord> rrList = commonDao.findByQuery(findrr,new String[]{"asnId","itemId"},new Object[]{asn.getId(),wmsItem.getId()});
                    for(WmsReceivedRecord record : rrList){
                        record.getSku().setItemId(item.getId());
                        commonDao.store(record);
                    }
                    String hql = "select inventory from WmsInventory inventory left join WmsBox box on box.inventoryId = inventory.id where inventory.pn = :pn and box.snNumber = :snNumber and box.status != 'S'";
                    WmsInventory inventory = (WmsInventory)commonDao.findByQueryUniqueResult(hql, new String[]{"pn", "snNumber"}, new Object[]{pnAndSnss[0], pnAndSnss[1]});
                    if(null != inventory){
                        inventory.getSku().setItemId(item.getId());
                        commonDao.store(inventory);
                    }
                    //修改唯一码明细的货品ID
                    String hql1 = "select content from WmsBoxContent content left join WmsBox box on box.id = content.boxId left join WmsInventory inventory on inventory.id = box.inventoryId " +
                            "where inventory.pn = :pn and box.snNumber = :snNumber and box.status != 'S' ";
                    List<WmsBoxContent> contentList = commonDao.findByQuery(hql1, new String[]{"pn", "snNumber"}, new Object[]{pnAndSnss[0], pnAndSnss[1]});
                    WmsItem finalItem = item;
                    contentList.forEach(f -> {
                        f.setItemId(finalItem.getId());
                        commonDao.store(f);
                    });
                }
            }
            //二次过账
            logger.info("二次过账开始：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
            wmsAsynManager.zwmsMIGOPOSTtcq2(asn, pnAndSn);
            logger.info("二次过账结束：" + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
        }
    }


    /*public void getSecondaryReceiptSn(String[] snCodes, WmsASN asn){
        for(int i = 0; i < snCodes.length; i++){
            String [] scanHu = snCodes[i].split("\\,");
            WebServiceSfc webServiceSfc = new DefaultWebServiceSfc(); //新建接口对象
            String sfcData = webServiceSfc.getCartonTable(scanHu[2]);
            JSONObject jsonObj= JSONObject.parseObject(sfcData);
            String code = jsonObj.getString("code");
            if("S".equals(code)) {
                JSONArray jsonData = jsonObj.getJSONArray("data");
                String sn = null, cartonNo = null, materialNo = null;
                for (int j = 0; j < jsonData.size(); j++) {
                    JSONObject ob = (JSONObject) jsonData.get(j);
                    sn = ob.getString("SN");
                    cartonNo = ob.getString("CartonNO");
                    materialNo = ob.getString("MaterialNo");
                }
                WmsReceivedRecord record = (WmsReceivedRecord)this.commonDao.findByQueryUniqueResult("from WmsReceivedRecord record where record.moveTool.boxNo = :hu and record.status = 'E' ", "hu", scanHu[2]);
                WmsItem item = (WmsItem)this.commonDao.findByQueryUniqueResult("from WmsItem item where item.code = :itemCode and item.status = 'E' ", "itemCode", materialNo);
                record.getSku().setItemId(item.getId());
                commonDao.store(record);
                WmsInventory inventory = (WmsInventory)commonDao.findByQueryUniqueResult("from WmsInventory inventory where inventory.moveTool.boxNo = :hu ", "hu", scanHu[2]);
                inventory.getSku().setItemId(item.getId());
                commonDao.store(inventory);
            }
        }
        //二次过账
        wmsAsynManager.zwmsMIGOPOSTtcq2(asn);
    }*/

    @Transactional
    public void getInterface(String huCode, WmsASN asn, WmsASNDetail detail, Map<String, Object> result){
        WmsWarehouse wmsWarehouse = WmsWarehouseHolder.get();
        WmsBillType bt = this.load(WmsBillType.class, asn.getBillTypeId());
        WmsItem asnItem = commonDao.load(WmsItem.class, detail.getItemId());
        //单据类型==工单收货
            List<WmsSnBindPn> snBindPnList = new ArrayList<>();
            List<WmsASNDetail> asnDetailList = new ArrayList<>();
            List<String> snPnList = new ArrayList<>();
            //判断扫的HU码是WMS还是SFC提供
            Pattern pattern = Pattern.compile("-?[0-9]+\\.?[0-9]*");
            String huSplit = huCode.substring(0, 6);
            //for (String hu : huCode){
            boolean strResult = pattern.matcher(huCode.substring(0,1)).matches();
            if(strResult || ("AYT20".equals(huSplit) || "BYT20".equals(huSplit) || "CYT20".equals(huSplit))){
                //WMS获取SN
                snBindPnList = this.commonDao.findByQuery("from WmsSnBindPn sn where 1 = 1 and sn.hu = :scanHu and sn.status = 'E' ", new String[]{"scanHu"}, new Object[]{huCode});
                //asnDetailList = this.commonDao.findByQuery("from WmsASNDetail detail where 1 = 1 and detail.asnId = :asnId ", new String[]{"asnId"}, new Object[]{asn.getId()});
                //WmsASNDetail detail = asnDetailList.get(0);//目前工单收货只有一条明细
                if(snBindPnList.size() > detail.getExpectedQty().getBaseQty()){
                    throw new RfBusinessException("SN对应的数量大于订单数量！");
                }
                if(snBindPnList.size() == 0){
                    throw new RfBusinessException("扫描的[" + huCode + "]在[sn绑定hu管理]中未维护！");
                }
                for(WmsSnBindPn snBindPn:snBindPnList){
                    WmsItem item = this.commonDao.load(WmsItem.class, detail.getItemId());
                    if(StringUtils.isEmpty(huCode)){
                        if(snBindPn.getSn().equals(snBindPn.getPn())){
                            throw new RfBusinessException("SN与PN相同！");
                        }
                    }
                    if("TCQGDSH".equals(bt.getCode())){
                        if (snBindPn.getItemNo().equals( item.getCode())){
                            throw new RfBusinessException("料号相同，业务错误");
                        }
                        String findPn = "SELECT ps FROM WmsPnSup ps left join WmsItem i on i.id = ps.itemId where i.code =:pn or ps.pn = :pn";
                        List<WmsPnSup> pnSups = commonDao.findByQuery(findPn,new String[]{"pn","pn"},new Object[]{snBindPn.getPn(),snBindPn.getPn()});
                        if (pnSups.size()>0){
                            /*WmsPnSup pnSup = pnSups.get(0);
                            item = commonDao.load(WmsItem.class,pnSup.getItemId());*/
                            if (snBindPn.getItemNo().equals(item.getCode())){
                                throw new RfBusinessException("料号相同，业务错误");
                            }
                        }
                        String findSnTr = "select st from WmsSnTraceability st left join WmsItem item on item.id = st.itemId where st.serialNo=:sn AND item.code = :pn and st.status = 'E' and st.postStatus = 'NP' ";
                        List<WmsSnTraceability> snTraceabilityList = commonDao.findByQuery(findSnTr,new String[]{"sn","pn"},new Object[]{snBindPn.getSn(), snBindPn.getItemNo()});
                        if (snTraceabilityList.size() == 0){
                            throw new RfBusinessException("该SN未在SN溯源记录表中维护！");
                        }
                    }else {
                        if (!snBindPn.getItemNo().equals(item.getCode())){
                            String findPn = "SELECT ps FROM WmsPnSup ps where ps.pn = :pn";
                            List<WmsPnSup> pnSups = commonDao.findByQuery(findPn,new String[]{"pn"},new Object[]{snBindPn.getPn()});
                            if (pnSups.size()>0){
                                WmsPnSup pnSup = pnSups.get(0);
                                WmsItem wmsItem = commonDao.load(WmsItem.class,pnSup.getItemId());
                                if (!item.getCode().equals(wmsItem.getCode())){
                                    throw new RfBusinessException("料号不相同，业务错误");
                                }
                            }else{
                                throw new RfBusinessException("料号不相同，业务错误");
                            }

                            /*if(!item.getCode().equals(snBindPn.getPn())){
                                String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
                                WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{item.getCode(), snBindPn.getPn()});
                                if(!snBindPn.getPn().equals(item.getCode())){
                                    if(null == wmsPnSup){
                                        throw new RfBusinessException("PN与料号对应错误！");
                                    }
                                }
                            }*/
                        }
                        String findSnTr = "select st from WmsSnTraceability st where st.serialNo=:sn and st.status = 'E' AND st.itemId = " +
                                "(SELECT max(ps.itemId) FROM WmsPnSup ps left join WmsItem i on i.id = ps.itemId where i.code =:pn or ps.pn = :pn) ";
                        List<WmsSnTraceability> snTraceabilityList = commonDao.findByQuery(findSnTr,new String[]{"sn","pn","pn"},new Object[]{snBindPn.getSn(),snBindPn.getPn(),snBindPn.getPn()});
                        if(WmsBillTypeCode.GDSH.equals(bt.getCode())){
                            if (snTraceabilityList.size() == 0){
                                throw new RfBusinessException("该SN未在SN溯源记录表中维护！");
                            }
                        }
                    }

                    //校验物料和SN是否在库，如果存在则提示‘物料与SN已在库’params.get("scanCodeSnOld")
                    String boxHql = "select box from WmsBox box left join WmsInventory inventory on inventory.id = box.inventoryId where 1 = 1 and box.snNumber = :scanCodeSn " +
                            "and inventory.sku.itemId = :itemId and box.status != 'S' ";
                    WmsBox wmsBox = null;
                    //if(StringUtils.isNotEmpty(huCode)){
                        wmsBox = (WmsBox) this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{snBindPn.getSn(), item.getId()});
                    /*}else{
                        wmsBox = (WmsBox)this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{huCode, item.getId()});
                    }*/
                    System.out.println("料号id="+item.getId()+",料号="+item.getCode()+",sn="+snBindPn.getSn()+",sn的料号="+snBindPn.getItemNo()+",pn="+snBindPn.getPn());
                    if(null != wmsBox){
                        throw new RfBusinessException("物料["+item.getCode()+"]与SN["+snBindPn.getSn()+"]已在库");
                    }
                    String snPn = snBindPn.getPn() +"," + snBindPn.getSn()+ "," +huCode;
                    snPnList.add(snPn);
                    if(null == result.get("pnAndSnAndLocaOld")){
                        result.put("pnAndSnAndLocaOld", snBindPn.getPn() + "," + snBindPn.getSn() + "," + huCode + "|");
                    }else{
                        result.put("pnAndSnAndLocaOld", result.get("pnAndSnAndLocaOld") + snBindPn.getPn() + "," + snBindPn.getSn() + "," + huCode + "|");
                    }
                }
            }else{
                //SFC获取SN
                //通过接口从SFC系统获取整机SN信息
                //如果是由SFC提供，则需要通过接口从SFC系统获取整机SN信息（通过HU获取SFC系统中HU下包含的SN、物料号）
                WebServiceSfc webServiceSfc = new DefaultWebServiceSfc(); //新建接口对象
                String sfcData = webServiceSfc.getCartonTable(huCode);
                JSONObject jsonObj= JSONObject.parseObject(sfcData);
                String code = jsonObj.getString("code");
                if("S".equals(code)){
                    JSONArray  jsonData = jsonObj.getJSONArray("data");
                    inInterfaceLogManager.createInInterfaceLog(
                            "SFC获取整机SN信息", SapConstants.SFC, huCode + "," + jsonData.size(), jsonObj.toString(), huCode, WmsApiLogStatus.SUCCESS);
                    String sn = null,cartonNo = null,materialNo = null,pn = null;
                    for(int j=0;j<jsonData.size();j++){
                        JSONObject ob = (JSONObject) jsonData.get(j);
                        sn = ob.getString("SN");
                        pn = ob.getString("PN");
                        cartonNo = ob.getString("CartonNO");//hu
                        materialNo = ob.getString("MaterialNo");//pn==itemCode
                        //String itemHql = "from WmsItem item where 1 = 1 and item.id in (:itemId) and item.code = :itemCode ";
                        //logger.info("SFC返回的信息：SN[" + sn + ",PN[" + pn + "],料号[" + materialNo +"]");
                        WmsItem item = (WmsItem)this.commonDao.findByQueryUniqueResult("from WmsItem item where 1 = 1 and item.code = :itemCode ",
                                new String[]{"itemCode"}, new Object[]{materialNo});
                        WmsPnSup wmsPnSup = (WmsPnSup)commonDao.findByQueryUniqueResult("from WmsPnSup sup where sup.itemId = :itemId and sup.pn = :pn and sup.status = 'E' ",
                                new String[]{"itemId", "pn"}, new Object[]{item.getId(), pn});
                        logger.info("第"+j+"个,扫描SFC的HU[" + huCode + "],货品ID[" +item.getId() + "],货品编码[" + item.getCode() + ",sn[" + sn + "],pn[" + pn + "],sfc返回的货品[" + materialNo + "]");
                        if(!pn.equals(materialNo)){
                            if(null == wmsPnSup){
                                throw new RfBusinessException("pn:" + pn + ",料号：" + item.getCode() + "对应关系没有维护");
                            }
                        }
                        if(WmsBillTypeCode.GDSH.equals(bt.getCode())){
                            if(!asnItem.getCode().equals(materialNo)){
                                throw new RfBusinessException("SFC返回的货品[" + materialNo + "]与该ASN的货品不一致！");
                            }
                        }else if(WmsBillTypeCode.TCQGDSH.equals(bt.getCode())){
                            if(asnItem.getCode().equals(materialNo)){
                                throw new RfBusinessException("SFC返回的货品[" + materialNo + "]与该ASN的货品一致！");
                            }
                        }else{
                            if(!asnItem.getCode().equals(materialNo)){
                                throw new RfBusinessException("SFC返回的货品[" + materialNo + "]与该ASN的货品不一致！");
                            }
                        }
                        if(WmsBillTypeCode.GDSH.equals(bt.getCode()) || WmsBillTypeCode.TCQGDSH.equals(bt.getCode())){
                            try {
                                String hql = "from WmsSnTraceability bil where bil.itemId = :itemNo and bil.serialNo = :serialNo and bil.status = 'E' and bil.postStatus = 'NP' ";
                                WmsSnTraceability wmsSnTraceability = (WmsSnTraceability)commonDao.findByQueryUniqueResult(hql, new String[]{"itemNo", "serialNo"}, new Object[]{item.getId(),sn});
                                if(null == wmsSnTraceability){
                                    throw new RfBusinessException("该SN[" + sn + "]未在SN溯源记录表中维护！");
                                }
                            }catch (Exception e){
                                throw new RfBusinessException("SN溯源信息查找失败！");
                            }
                        }
                        //校验物料和SN是否在库，如果存在则提示‘物料与SN已在库’params.get("scanCodeSnOld")
                        String boxHql = "select box from WmsBox box left join WmsInventory inventory on inventory.id = box.inventoryId left join WmsItem item on item.id = inventory.sku.itemId where 1 = 1 and box.snNumber = :scanCodeSn " +
                                "and item.id = :itemId and box.status != 'S' ";
                        WmsBox wmsBox = null;
                        if(StringUtils.isNotEmpty(huCode)){
                            wmsBox = (WmsBox) this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{sn, item.getId()});
                        }else{
                            wmsBox = (WmsBox)this.commonDao.findByQueryUniqueResult(boxHql, new String[]{"scanCodeSn", "itemId"}, new Object[]{huCode, item.getId()});
                        }
                        if(null != wmsBox){
                            throw new RfBusinessException("物料[" + item.getCode() + "]与SN[" + sn + "]已在库");
                        }

                        String snPn = materialNo +"," + sn+ "," +huCode;
                        snPnList.add(snPn);
                        if(null == result.get("pnAndSnAndLocaOld")){
                            result.put("pnAndSnAndLocaOld", pn + "," + sn + "," + cartonNo + "|");
                        }else{
                            result.put("pnAndSnAndLocaOld", result.get("pnAndSnAndLocaOld") + pn + "," + sn + "," + cartonNo + "|");
                        }
                    }
                    result.put("snCount", jsonData.size());
                }else{
                    String message = jsonObj.getString("message");
                    throw new RfBusinessException(message + ",SFC获取失败！");
                }
            }
    }

    public Map checkItemNo(Map params){
        //解析扫码HU的值
        String scanCodeHuNew = params.get("scanCodeHu") == null ? "" : params.get("scanCodeHu").toString();
        String itemCodeNew = "", quantityNew = "", batchInfoNew = "", scanHuNew = "";
        StringBuffer scanHu = new StringBuffer();
        StringBuffer scanItemCode = new StringBuffer();
        String [] scanCodeHuSplit = new String[0];
        if(scanCodeHuNew.length() == 19){//扫码为条形码
            throw new RfBusinessException("格式有误！");
        }else{
            if(StringUtils.isNotEmpty(scanCodeHuNew)){
                scanCodeHuSplit = scanCodeHuNew.split("\\^");
                //[0]-物料编码 [1]-数量 [2]-生产日期 [3]-批次号 [4]-一维码的前5位 [5]-一维码==HU码
                itemCodeNew = scanCodeHuSplit[0];
                quantityNew = scanCodeHuSplit[1];
                batchInfoNew = scanCodeHuSplit[3];
                scanHuNew = scanCodeHuSplit[5];
            }
        }
        String scanCodeHuHis = params.get("scanCodeHuOld") == null ? "" : params.get("scanCodeHuOld").toString();
        if(StringUtils.isEmpty(scanCodeHuHis) && StringUtils.isEmpty(scanCodeHuNew)){
            throw new RfBusinessException("HU码为空");
        }

        String asnId = params.get("asnId") == null ? null : params.get("asnId").toString();
        String detailId = params.get("detailId") == null ? null : params.get("detailId").toString();
        //获取asn单下所有物料
        List<WmsASNDetail> detailList = this.commonDao.findByQuery("from WmsASNDetail details where 1 = 1 and details.asnId = :asnId ", new String[]{"asnId"}, new Object[]{Long.valueOf(asnId)});
        List<Long> asnIds = new ArrayList<>();
        asnIds = detailList.stream().map(WmsASNDetail::getItemId).collect(Collectors.toList());
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        WmsBillType billType = this.commonDao.load(WmsBillType.class, asn.getBillTypeId());
        String itemHql = "from WmsItem item where 1 = 1 and item.id in (:itemId) and item.code = :itemCode ";
        WmsItem wmsItem = (WmsItem)this.commonDao.findByQueryUniqueResult(itemHql, new String[]{"itemId", "itemCode"}, new Object[]{asnIds, itemCodeNew});
        List<WmsASNDetail> wmsASNDetail = this.commonDao.findByQuery("from WmsASNDetail detail where detail.asnId = :asnId and detail.itemId = :itemId ",
                new String[]{"asnId","itemId"}, new Object[]{Long.valueOf(asnId), wmsItem.getId()});
        if("URGENT".equals(wmsASNDetail.get(0).getUrgentState()) || "".equals(wmsASNDetail.get(0).getUrgentState())){
            params.put("forwardConfirmCode", "此物料为加急物料！");
        }
        if("TCQGDSH".equals(billType.getCode())){
            if(!(params.get("scanCodeHuOld").toString()).contains(itemCodeNew)){
                throw new RfBusinessException("扫描的HU的货品与第一次不一致！");
            }
        }
        if(!"TCQGDSH".equals(billType.getCode())) {
            if (null == wmsItem) {
                throw new RfBusinessException("料号不存在！");
            }
        }


        //校验hu里的数量与溯源记录里面的数量是否一致，若小于则找出缺的sn
        String SnTHql = "from WmsSnTraceability where status='E' and serialNo in (select sn from WmsSnBindPn where hu=:hu)";
        List<WmsSnTraceability> stList = commonDao.findByQuery(SnTHql,"hu",scanHuNew);
        String lackSn = "";//缺的sn
        if (stList.size() < Double.parseDouble(quantityNew)){
            String findSnBindPn = "from WmsSnBindPn where hu=:hu";
            List<WmsSnBindPn> snBindPnsList = commonDao.findByQuery(findSnBindPn,"hu",scanHuNew);
            for (WmsSnBindPn snBindPn:snBindPnsList){
                String sn = snBindPn.getSn();
                for(int i= 0;i<stList.size();i++){
                    System.out.println("stList.get(0).getSerialNo())"+stList.get(0).getSerialNo());
                    if (sn.equals(stList.get(i).getSerialNo())){
                        break;
                    } else if (i == stList.size()-1){
                        lackSn = lackSn + sn + ";";
                    }
                }
            }
            if (lackSn.length()>0){
                throw new RfBusinessException("溯源记录中缺少sn："+lackSn);
            }
        }

        int remind = (params.get("remindHis") == null || "".equals(params.get("remindHis"))) ? 0 : Integer.parseInt(params.get("remindHis").toString());
        if(WmsBillTypeCode.CGSH.equals(billType.getCode()) || WmsBillTypeCode.WXRKYZ.equals(billType.getCode())
                    || WmsBillTypeCode.MFJH.equals(billType.getCode()) || WmsBillTypeCode.FWLX.equals(billType.getCode())){
            if(remind == 0){
                if(wmsItem.getBeWarehouseQua()){
                    if(null != wmsItem.getQuaWorkAreaId()){
                        remind += 1;
                        WmsWorkArea workArea = this.commonDao.load(WmsWorkArea.class, wmsItem.getQuaWorkAreaId());
                        params.put("forwardConfirmCode", "该货品需要入库质检，质检区域为：" + workArea.getDescription());
                        params.put("remindHis", String.valueOf(remind));
                    }else{
                        remind += 1;
                        params.put("remindHis", String.valueOf(remind));
                        params.put("forwardConfirmCode", "该货品需要入库质检，质检区域为：" + wmsItem.getQuaWorkAreaId());
                    }
                }
            }
        }
        return params;
    }

    public Map snCheck(Map params){
        String asnId = params.get("asnId") == null ? null : params.get("asnId").toString();
        String detailId = params.get("detailId") == null ? null : params.get("detailId").toString();
        String itemCode = params.get("itemCode") == null ? null : params.get("itemCode").toString();
        //获取asn单下所有物料
        List<WmsASNDetail> detailList = this.commonDao.findByQuery("from WmsASNDetail details where 1 = 1 and details.asnId = :asnId ", new String[]{"asnId"}, new Object[]{Long.valueOf(asnId)});
        List<Long> asnIds = new ArrayList<>();
        asnIds = detailList.stream().map(WmsASNDetail::getItemId).collect(Collectors.toList());
        WmsASNDetail detail = this.commonDao.load(WmsASNDetail.class, Long.parseLong(detailId));
        WmsASN asn = this.commonDao.load(WmsASN.class, detail.getAsnId());
        WmsBillType billType = this.commonDao.load(WmsBillType.class, asn.getBillTypeId());
        String itemHql = "from WmsItem item where 1 = 1 and item.id in (:itemId) and item.code = :itemCode ";
        WmsItem wmsItem = (WmsItem)this.commonDao.findByQueryUniqueResult(itemHql, new String[]{"itemId", "itemCode"}, new Object[]{asnIds, itemCode});
        int remind = (params.get("remind") == null || "".equals(params.get("remind"))) ? 0 : Integer.parseInt(params.get("remind").toString());
        if(WmsBillTypeCode.CGSH.equals(billType.getCode()) || WmsBillTypeCode.WXRKYZ.equals(billType.getCode())
                || WmsBillTypeCode.MFJH.equals(billType.getCode()) || WmsBillTypeCode.FWLX.equals(billType.getCode())){
            if(remind == 0){
                if(wmsItem.getBeWarehouseQua()){
                    if(null != wmsItem.getQuaWorkAreaId()){
                        remind += 1;
                        WmsWorkArea workArea = this.commonDao.load(WmsWorkArea.class, wmsItem.getQuaWorkAreaId());
                        params.put("forwardConfirmCode", "该货品需要入库质检，质检区域为：" + workArea.getDescription());
                        params.put("remind", remind);
                    }else{
                        remind += 1;
                        params.put("remind", remind);
                        params.put("forwardConfirmCode", "该货品需要入库质检，质检区域为：" + wmsItem.getQuaWorkAreaId());
                    }
                }
            }
        }
        return params;
    }

    public Map chooseSubmit(Map params){
        String chooseType = MapUtils.getStringNotNull(params, "chooseType", "chooseType.is.null");
        if("sn&&pn扫描".equals(chooseType)){
            params.put(RfConstant.FORWARD_VALUE, "snSuccess");
        }else if("二维码扫描".equals(chooseType)){
            params.put(RfConstant.FORWARD_VALUE, "qrSuccess");
        }
        return params;
    }

    public Map chooseSubmit1(Map params){
        String chooseType = MapUtils.getStringNotNull(params, "chooseType", "chooseType.is.null");
        if("sn&&pn扫描".equals(chooseType)){
            params.put(RfConstant.FORWARD_VALUE, "snSuccess");
        }else if("二维码扫描".equals(chooseType)){
            params.put(RfConstant.FORWARD_VALUE, "qrSuccess");
        }
        return params;
    }

    /**
     * 借用返还==JYFH 增加溯源记录
     * WmsSnTraceability
     */
    private void jyfhAddTraceability(WmsItem item, String sn) {
        WmsSnTraceability wmsSnTraceability = (WmsSnTraceability) commonDao.findByQueryUniqueResult("from WmsSnTraceability bility where bility.itemId = :itemId and bility.serialNo = :serialNo and bility.status = 'E' ",
                new String[]{"itemId", "serialNo"}, new Object[]{item.getId(), sn});
        if(null == wmsSnTraceability){
            //溯源记录新增数据
            wmsSnTraceability.setItemId(item.getId());
            wmsSnTraceability.setItemDescription(item.getName());
            wmsSnTraceability.setSerialNo(sn);
            this.commonDao.store(wmsSnTraceability);
        }
    }

    void parseExpireDate(Long itemId,String supplierCode,WmsItemKey itemKey) {
        String samHql = "select sample.warrantyPer FROM WmsCycleSample sample " +
                "LEFT JOIN WmsSupplier supplier ON supplier.id=sample.cycleSupplierId " +
                "WHERE sample.cycleItemId=:itemId and supplier.code=:supplierCode and sample.status='E'";
        List<Integer> warrant = commonDao.findByQuery(samHql, new String[]{"itemId", "supplierCode"},
                new Object[]{itemId, supplierCode});

        if (!warrant.isEmpty() && warrant.get(0) != null && itemKey.getLotInfo().getProductDate() != null) {
		 /*  SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
		   Calendar calendar = Calendar.getInstance();
		   try {
			   calendar.setTime(sdf.parse(productionDate));
		   } catch (Exception e) {
			   e.printStackTrace();
		   }
		   calendar.add(Calendar.DATE, warrant.get(0).intValue());*/
            itemKey.getLotInfo().setExpireDate(com.vtradex.wms.baseserver.utils.DateUtils.addDateHour(itemKey.getLotInfo().getProductDate(), warrant.get(0).intValue() * 24));
        }
    }
}
