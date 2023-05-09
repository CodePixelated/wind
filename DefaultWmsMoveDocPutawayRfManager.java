package com.vtradex.wms.rfserver.service.receiving.pojo;

import com.vtradex.rf.common.RfConstant;
import com.vtradex.rf.common.exception.RfBusinessException;
import com.vtradex.thorn.server.model.EntityFactory;
import com.vtradex.thorn.server.model.task.ThornTask;
import com.vtradex.thorn.server.util.LocalizedMessage;
import com.vtradex.thorn.server.web.security.UserHolder;
import com.vtradex.wms.baserfserver.service.receiving.pojo.AbstractWmsMoveDocPutawayRfManager;
import com.vtradex.wms.baseserver.holder.WmsWarehouseHolder;
import com.vtradex.wms.baseserver.holder.WmsWorkerHolder;
import com.vtradex.wms.baseserver.service.movedoc.WmsMoveDocAsynManager;
import com.vtradex.wms.baseserver.service.movedoc.WmsMoveDocPIManager;
import com.vtradex.wms.baseserver.service.receiveing.WmsQualityInspectionManager;
import com.vtradex.wms.baseserver.utils.*;
import com.vtradex.wms.server.model.component.Sku;
import com.vtradex.wms.server.model.dto.WmsInventoryPlusDto;
import com.vtradex.wms.server.model.dto.WmsInventoryPlusDtoFactory;
import com.vtradex.wms.server.model.entity.advanced.WmsErpWh;
import com.vtradex.wms.server.model.entity.advanced.WmsPnSup;
import com.vtradex.wms.server.model.entity.advanced.WmsSnTraceability;
import com.vtradex.wms.server.model.entity.cycleSample.WmsCycleSample;
import com.vtradex.wms.server.model.entity.inventory.WmsBox;
import com.vtradex.wms.server.model.entity.inventory.WmsInventory;
import com.vtradex.wms.server.model.entity.inventory.WmsItemKey;
import com.vtradex.wms.server.model.entity.item.WmsItem;
import com.vtradex.wms.server.model.entity.movedoc.WmsMoveDoc;
import com.vtradex.wms.server.model.entity.movedoc.WmsTask;
import com.vtradex.wms.server.model.entity.receiving.*;
import com.vtradex.wms.server.model.entity.warehouse.*;
import com.vtradex.wms.server.model.enums.WmsBillTypeCode;
import com.vtradex.wms.server.model.enums.WmsCycleSampleType;
import com.vtradex.wms.server.model.enums.WmsMoveDocType;
import com.vtradex.wms.server.model.enums.WmsTaskStatus;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.util.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RF收货上架manager
 *
 * @author <a href="mailto:xiansheng.hu2@vtradex.net">胡宪胜</a>
 * @since 2020/3/19 下午5:36
 */
@Service(value = "wmsMoveDocPutawayRfManager")
@Transactional(readOnly = true)
public class DefaultWmsMoveDocPutawayRfManager extends AbstractWmsMoveDocPutawayRfManager {
    @Autowired
    protected WmsMoveDocPIManager wmsMoveDocPIManager;
    @Autowired
    protected WmsQualityInspectionManager wmsQualityInspectionManager;

    /**
     * 上架入口
     * @param param
     * @return
     */
    @Override
    public Map getPutEntrance(Map param) {
        Map result = new HashMap();
        String tdId = param.get("tdId") == null ? null : param.get("tdId").toString();
        if("8".equals(tdId)){
            result.put(RfConstant.FORWARD_VALUE, "ptPutaway");
        }else{
            result.put(RfConstant.FORWARD_VALUE, "snPutaway");
        }
        return result;
    }

    /**
     * 普通上架列表
     * @param param
     * @return
     */
    @Override
    public Map getPtPutAway(Map param) {
        Map result = new HashMap();
        String moveDocId = param.get("moveDocId") == null ? null : param.get("moveDocId").toString();
        WmsMoveDoc moveDoc = this.commonDao.load(WmsMoveDoc.class, Long.parseLong(moveDocId));
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put("moveDocCode", moveDoc.getCode());
        result.putAll(param);
        return result;
    }

    /**
     * 普通上架-确认(初始化加载)
     * @param param
     * @return
     */
    @Override
    public Map getPtPutawatConfirm(Map param) {
        Map result = new HashMap();
        result.putAll(param);
        return result;
    }

    /**
     * 普通上架-确认按钮
     * @param param
     * @return
     */
    @Override
    public Map findPtPutawayConfirm(Map param) {
        Map result = new HashMap();
        result.putAll(param);
        String locationCode = "";
        String itemCodeNew = "", quantityNew = "", batchInfoNew = "", scanHuNew = "";
        Double quantity = 0D;
        StringBuffer scanHu = new StringBuffer();
        StringBuffer scanItemCode = new StringBuffer();
        String [] scanCodeHuSplit = new String[0];

        //如果是不良品，则调打印
        /*Map map = checkedItemCode(param);
        if("1".equals(map.get("flag"))){
            //调打印方法
            String newhuCode = wmsMoveDocPIManager.creatHUCode(supplier.getCode());
            result.put(RfCode.TYPE_ASYN_RESULT_KEY, RfCode.TYPE_ASYN_NOT_FINISHED);
            result.put(RfCode.TYPE_ASYN_RESULT_MSG, map.get("forwardConfirmCode"));
            result.put(RfConstant.FORWARD_CONFIRM_CODE, map.get("forwardConfirmCode"));
            return result;
        }*/
        //String scanCodeHu =param.get("scanCodeHu") == null ? "" : param.get("scanCodeHu").toString();
        WmsWarehouse warehouse = WmsWarehouseHolder.get();
        //解析扫码HU的值
        String scanCodeHuNew = param.get("scanCodeHu") == null ? "" : param.get("scanCodeHu").toString();
        if(StringUtils.isEmpty(scanCodeHuNew)){
            throw new RfBusinessException("HU码为空！");
        }

        int scanCount = Integer.valueOf(param.get("scanCount") == null ? "0" : param.get("scanCount").toString()) + 1;
//        if(scanCount > 10){
//            throw new RfBusinessException("HU扫描次数超过上限！");
//        }

        //通过hu查询库存的库存状态
        List<WmsInventory> inventoryList = null;
        if(scanCodeHuNew.contains("^")){
            scanCodeHuSplit = scanCodeHuNew.split("\\^");
            inventoryList = this.commonDao.findByQuery("from WmsInventory inv where inv.moveTool.palletNo = :hu or inv.moveTool.outBoxNo = :hu or inv.moveTool.innerBoxNo = :hu " +
                    "or inv.moveTool.boxNo = :hu ", "hu", scanCodeHuSplit[5]);
        }else{
            inventoryList = this.commonDao.findByQuery("from WmsInventory inv where inv.moveTool.palletNo = :hu or inv.moveTool.outBoxNo = :hu or inv.moveTool.innerBoxNo = :hu " +
                    "or inv.moveTool.boxNo = :hu ", "hu", scanCodeHuNew);
        }
        if(inventoryList.size() > 0){
            for(WmsInventory inventory : inventoryList){
                if("待检".equals(inventory.getSku().getInventoryStatus())){
                    throw new RfBusinessException("扫描的[" + inventory.getMoveTool().getBoxNo() + "]在库存中的状态为待检，不允许上架操作！");
                }
            }
        }

        if(scanCodeHuNew.contains("^")){
            if(StringUtils.isNotEmpty(scanCodeHuNew)){
                scanCodeHuSplit = scanCodeHuNew.split("\\^");
                //[0]-物料编码 [1]-数量 [2]-生产日期 [3]-批次号 [4]-一维码的前5位 [5]-一维码==HU码
                itemCodeNew = scanCodeHuSplit[0];
                quantityNew = scanCodeHuSplit[1];
                batchInfoNew = scanCodeHuSplit[3];
                scanHuNew = scanCodeHuSplit[5];
            }
        }else{
            //如果扫描的是一维码作业，先查询库存
            /*String hql = "FROM WmsInventory inv WHERE  inv.moveTool.boxNo = :boxNo and inv.operationStatus = 'O' AND inv.qty.baseQty > 0 ";
            List<WmsInventory> wmsInventoryList = this.commonDao.findByQuery(hql, "boxNo", scanCodeHuNew);*/
            if(inventoryList.size() == 0){
                throw new RfBusinessException("扫描的[" + scanCodeHuNew + "]在库存中不存在");
            }
            StringBuffer sb = new StringBuffer();
            for(WmsInventory inventory : inventoryList){
                //累加库存中hu的数量
                quantity = quantity + inventory.getQty().getBaseQty();
            }
            WmsItem item = this.commonDao.load(WmsItem.class, inventoryList.get(0).getSku().getItemId());
            WmsItemKey itemKey = this.commonDao.load(WmsItemKey.class, inventoryList.get(0).getSku().getItemKeyId());
            DateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            Date productDate = itemKey.getLotInfo().getProductDate();
            try{
                sb.append(item.getCode()).append("^").append(quantity).append("^");
                if(null!=productDate){
                    sb.append(sdf.format(productDate));
                }

                sb.append("^").append(itemKey.getLotInfo().getLot()).append("^").append(scanCodeHuNew.substring(0, 5)).append("^").append(scanCodeHuNew);
            }catch(Exception e){
                e.printStackTrace();
            }
            scanCodeHuNew = sb.toString();
        }

        String taskHql = "from WmsTask task where 1 = 1 and ( task.moveTool.palletNo = :scanHu or task.moveTool.outBoxNo = :scanHu or task.moveTool.innerBoxNo = :scanHu or task.moveTool.boxNo = :scanHu ) " +
                "and task.warehouseId = :warehouseId and task.status = 'WK' ";
        scanCodeHuSplit = scanCodeHuNew.split("\\^");
        List<WmsTask> wmsTaskList = this.commonDao.findByQuery(taskHql, new String[]{"scanHu", "warehouseId"}, new Object[]{scanCodeHuSplit[5], warehouse.getId()});//解析二维码之后的hu
        if(wmsTaskList.size() == 0){
            throw new RfBusinessException("非待上架HU!");
        }

        WmsLocation location = null;
        String receiveLocationCode = param.get("receiveLocationCode") == null ? "" : param.get("receiveLocationCode").toString();
        if(StringUtils.isNotEmpty(receiveLocationCode)){
            location = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'ST' ", "code", receiveLocationCode);
            locationCode=location.getCode();
        }else{
            List<Long> loc1 = new ArrayList<Long>();
            List<Long> loc2 = new ArrayList<Long>();
            String loc3 =null;
            String hql = "select DISTINCT location from WmsInventory inventory " +
                    "left join WmsLocation location on location.id=inventory.locationId " +
                    "left join WmsStoreArea storeArea on storeArea.id = location.putStoreAreaId " +
                    "  where inventory.sku.itemId=:itemId  " + //and location.putStoreAreaId=:putStoreAreaId " +
                    " and location.status = 'E' " +
                    " AND location.code not in ('JHKW','GYSKW','XNKW') and location.code not like 'LYKW%' " +
                    " AND location.type='ST' "+
                    " AND inventory.qty.baseQty>0.0   ORDER BY location.id ";
            List<WmsLocation> wmsLocationList = this.commonDao.findByQueryMaxNum(hql, new String[]{"itemId"},
                    new Object[]{inventoryList.get(0).getSku().getItemId()},0,5); //wmsLocation.getPutStoreAreaId()},
            for(WmsLocation wmsloc :wmsLocationList){
                if(wmsloc.getLocationCapacityId()==5||wmsloc.getLocationCapacityId()==6) {
                    if (wmsloc.getLocationCapacityId()==6 && wmsloc.getTouchTimes() > 0) {
                        loc1.add(wmsloc.getId());
                    } else if (wmsloc.getLocationCapacityId()==5 && wmsloc.getTouchTimes() > 0) {
                        loc2.add(wmsloc.getId());
                    }
                }else{
                    List<String>  l = (List<String>) this.commonDao.findByQuery("SELECT DISTINCT location.code from WmsLocation location left join WmsLocationCapacity cap on cap.id=location.locationCapacityId where location.status = 'E' and  location.type = 'ST' and cap.id=5 and location.touchTimes=0 ");
                    loc3=l.get(0);
                }
            }

            if(loc1.size()!=0){
                for(int i=0 ; loc1.size()>i;i++) {
                    Long log = (Long) commonDao.findByQueryUniqueResult("SELECT locationId from WmsInventoryLog l  WHERE l.id =( SELECT max(id) from WmsInventoryLog g WHERE g.locationId = :loc )", "loc", Long.valueOf(loc1.get(i)));
                    String l = (String) this.commonDao.findByQueryUniqueResult("SELECT DISTINCT location.code from WmsLocation location where location.id=:id ","id",log);
                    locationCode = l;
                }
            }else if(loc2.size()!=0 &&locationCode==""){
                for(int i=0 ; loc2.size()>i;i++) {
                    Long log = (Long) commonDao.findByQueryUniqueResult("SELECT locationId from WmsInventoryLog l  WHERE l.id =( SELECT max(id) from WmsInventoryLog g WHERE g.locationId = :loc )", "loc", Long.valueOf(loc2.get(i)));
                    String l = (String) this.commonDao.findByQueryUniqueResult("SELECT DISTINCT location.code from WmsLocation location where location.id=:id ","id",log);
                    locationCode = l;
                }
            }else if((loc3!=null&&locationCode=="")){
                locationCode=loc3;
            }

//            if (null != wmsLocationList && wmsLocationList.size() > 0) {
//                for (String loc : wmsLocationList) {
//                    locationCode = locationCode + loc + ",";
//                }
//            }
        }
        if(null == locationCode){
            throw new RfBusinessException("扫描的收货库位在系统中未维护！");
        }
        //判断是否为高架库位
        /*WmsLocationCapacity locationCapacity = this.commonDao.load(WmsLocationCapacity.class, location.getLocationCapacityId());
        if("G".equals(locationCapacity.getBearingType())){
            if(StringUtils.isEmpty(param.get("libraryFull").toString())){
                throw new RfBusinessException("扫描的库位[" + location.getCode() + "]为高位货架库位,库满度不能为空！");
            }
        }*/
        WmsItem wmsItem = commonDao.load(WmsItem.class, wmsTaskList.get(0).getSku().getItemId());

        String allHu = null;
        String huList = null;
        String locationType="";
        if(wmsTaskList.size() > 0){
                List<WmsTask> wmsTasks=commonDao.findByQuery("from WmsTask task where task.moveTool.boxNo=:hu ","hu",scanCodeHuSplit[5]);
                allHu = result.get("allHu") == null ? "" : result.get("allHu").toString();
                huList = result.get("huList") == null ? null : result.get("huList").toString();
                result.put("allHu", allHu + "\n" + wmsTasks.get(0).getMoveTool().getBoxNo() + "," + wmsTasks.get(0).getQty().getBaseQty());
                locationType=wmsTasks.get(0).getLocationType();
                result.put("huList", huList == null ? wmsTasks.get(0).getMoveTool().getBoxNo() : huList + "," +  wmsTasks.get(0).getMoveTool().getBoxNo());
                if((huList == null ? "" : huList).contains(wmsTasks.get(0).getMoveTool().getBoxNo())){
                throw new RfBusinessException("HU重复!");
                }
        }
        /*else{
            allHu = result.get("allHu") == null ? "" : result.get("allHu").toString();
            huList = result.get("huList") == null ? null : result.get("huList").toString();
            result.put("allHu", allHu + "\n" + wmsTaskList.get(0).getMoveTool().getBoxNo() + "," + wmsTaskList.get(0).getQty().getBaseQty());
            result.put("huList", huList == null ? wmsTaskList.get(0).getMoveTool().getBoxNo() : huList + "," +  wmsTaskList.get(0).getMoveTool().getBoxNo());
            if((huList == null ? "" : result.get("huList").toString()).contains(allHu)){
                throw new RfBusinessException("HU重复!");
            }
        }*/

        WmsItemKey itemKey = commonDao.load(WmsItemKey.class, inventoryList.get(0).getSku().getItemKeyId());
        WmsErpWh wh = commonDao.load(WmsErpWh.class, itemKey.getLotInfo().getErpId());
        if(null != wh){
            result.put("factoryId", wh.getCode());//工厂
            result.put("inventoryPlace", wh.getInventoryPlace());//库存地点
        }

        result.put("scanCount", scanCount);//扫描次数
        result.put("itemCode", wmsItem.getCode());
        result.put("toLocationCode", locationCode +"("+locationType+")");//推荐库位
        result.put("recommendLocationCode", locationCode);//推荐库位
        result.put("receiveLocationCode", receiveLocationCode);//确认库位
        result.put("libraryFull", param.get("libraryFull").toString());
        result.put("scanCodeHu", "");//清除HU
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    /**
     * 按上架单散货上架-开始上架（普通上架）
     *
     * @param param
     * @return
     */
    @Override
    @Transactional
    public Map beginPTPutawayForMoveDocPut(Map param) {
        Map result = new HashMap();
        result.putAll(param);
        //WmsWarehouse warehouse = WmsWarehouseHolder.get();
        String receiveLocationCode = param.get("receiveLocationCode") == null ? "" : param.get("receiveLocationCode").toString();
        boolean beCheck=false;//如果是输入上架库位，那我就验证上架库位和任务明细是否是同一混放分组，不是就报错
        if(StringUtils.isNotEmpty(receiveLocationCode)){
            beCheck=true;
            receiveLocationCode = param.get("receiveLocationCode").toString();
        }else{
            receiveLocationCode = param.get("recommendLocationCode").toString();
        }

        WmsLocation location = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'ST' ", "code", receiveLocationCode);
        if(null == location){
            throw new RfBusinessException("扫描的收货库位在系统中未维护！");
        }
        //判断是否为高架库位
        WmsLocationCapacity locationCapacity = this.commonDao.load(WmsLocationCapacity.class, location.getLocationCapacityId());
        if("G".equals(locationCapacity.getBearingType())){
            if(StringUtils.isEmpty(param.get("libraryFull").toString())){
                throw new RfBusinessException("扫描的库位 " + receiveLocationCode + "]为高位货架库位,库满度不能为空！");
            }
        }
        String [] hus = param.get("huList").toString().split("\\,");
        List<String> huList = new ArrayList<>();
        for(int i = 0; i < hus.length; i++){
            if(!huList.contains(hus[i])){
                huList.add(hus[i]);
            }
        }
        for(String hu : huList){
            String inventoryHql = "from WmsInventory inventory where inventory.moveTool.boxNo = :scanHu and inventory.qty.baseQty > 0 ";
            List<WmsInventory> inventoryList = commonDao.findByQuery(inventoryHql, "scanHu", hu);
            //String taskHql = "from WmsTask task where 1 = 1 and task.moveTool.boxNo = :scanHu and task.warehouseId = :warehouseId ";
            //List<WmsTask> wmsTaskList = this.commonDao.findByQuery(taskHql, new String[]{"scanHu", "warehouseId"}, new Object[]{hu, warehouse.getId()});
            for(WmsInventory inventory : inventoryList){
                WmsTask wmsTask = commonDao.load(WmsTask.class, inventory.getTaskId());
                WmsMoveDoc moveDoc = commonDao.load(WmsMoveDoc.class, wmsTask.getMoveDocId());
                logger.info("操作人[" + UserHolder.getUser().getName() + "],库存ID[" + inventory.getId() + "],上架作业ID[" + moveDoc.getId() + "],作业类型[" + moveDoc.getType() + "],上架任务ID[" + wmsTask.getId() + "]");
                if(WmsMoveDocType.PU.equals(moveDoc.getType())){
                    WmsLocation location1=this.commonDao.get(WmsLocation.class,wmsTask.getToLocId());
                    if(beCheck&&!"XNKW".equals(location1.getCode())){//验证混放分组是否一致
                        checkReleaseGroup(location,inventory);
                    }
                    putawayConfirmForContainer(receiveLocationCode, wmsTask.getId(), wmsTask.getQty().getBaseQty());
                    //库满度存进库位
                    String libraryFull = param.get("libraryFull") == null ? "" : param.get("libraryFull").toString();
                    if(StringUtils.isNotEmpty(libraryFull)){
                        location.setLibraryFull(libraryFull);
                        commonDao.store(location);
                    }
                }else{
                    throw new RfBusinessException("无上架任务，不允许上架操作！");
                }
            }
            /*for(WmsTask wmsTask : wmsTaskList){
                WmsLocation location1=this.commonDao.get(WmsLocation.class,wmsTask.getToLocId());
                if(beCheck&&!"XNKW".equals(location1.getCode())){//验证混放分组是否一致
                    checkReleaseGroup(location,inventoryList.get(0));
                }
                putawayConfirmForContainer(receiveLocationCode, wmsTask.getId(), wmsTask.getQty().getBaseQty());
                //库满度存进库位
                String libraryFull = param.get("libraryFull") == null ? "" : param.get("libraryFull").toString();
                if(StringUtils.isNotEmpty(libraryFull)){
                    location.setLibraryFull(libraryFull);
                    commonDao.store(location);
                }
            }*/
            //commonDao.executeBySQL(updHql, new String[]{"code"}, new Object[]{hu});
        }

        //beginPutawayWithoutTakeForMoveDocPut(param);
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put(RfConstant.CLEAR_VALUE, true);
        return result;
    }

    /**
     * 分组
     */
    private String custKey(Map<String, String> map) {
        return map.get("hu").toString();
    }

    private void checkReleaseGroup(WmsLocation location,WmsInventory inventory){
        WmsLocation locationTemp=this.commonDao.get(WmsLocation.class, inventory.getLocationId());
        long zoneIdTemp=locationTemp.getZoneId();//库存上的库位的库区
        long zoneId=location.getZoneId();//推荐库位或确认库位的库区
        if(zoneIdTemp!=zoneId){//如果两个库区不相等，就比较两个库区对应的混放分组是否相等
            WmsItemKey itemKey = commonDao.load(WmsItemKey.class, inventory.getSku().getItemKeyId());
            WmsErpWh wh = commonDao.load(WmsErpWh.class, itemKey.getLotInfo().getErpId());
            //WmsZone wmsZone_temp=this.commonDao.get(WmsZone.class,zoneIdTemp);//task任务上的库位的库区(旧逻辑)
            WmsZone wmsZone=this.commonDao.get(WmsZone.class,zoneId);//推荐库位或确认库位的库区
            /*if(null==wmsZone_temp){
                throw new RfBusinessException("ID号为("+zoneIdTemp+")对应库区为空");
            }*/
            if(null == wh){
                throw new RfBusinessException("工厂：" + wh.getCode() + ",库存地点：" + wh.getInventoryPlace() + "数据有误！");
            }

            if(null==wmsZone){
                throw new RfBusinessException("ID号为("+zoneId+")对应库区为空");
            }

            /*if(wmsZone_temp.getReleaseGroup()==null){
                throw new RfBusinessException("库区（"+wmsZone_temp.getName()+"）对应混放分组为空");
            }*/

            if(null == wh.getReleaseGroup()){
                throw new RfBusinessException("工厂：" + wh.getCode() + ",库存地点：" + wh.getInventoryPlace() + "对应混放分组为空");
            }

            /*if(null==wmsZone.getReleaseGroup()||!wmsZone_temp.getReleaseGroup().equals(wmsZone.getReleaseGroup())){
                throw new RfBusinessException("推荐的混放分组("+wmsZone_temp.getReleaseGroup()+")和上架混放分组("+wmsZone.getReleaseGroup()+")不一致");
            }*/

            if(null==wmsZone.getReleaseGroup() || !wmsZone.getReleaseGroup().contains(wh.getReleaseGroup())){
                //throw new RfBusinessException("推荐的混放分组("+wmsZone_temp.getReleaseGroup()+")和上架混放分组("+wmsZone.getReleaseGroup()+")不一致");
                throw new RfBusinessException("库存上对应的[工厂][库存地点]的混放分组(" + wh.getReleaseGroup() + ")不存在推荐的混放分组(" + wmsZone.getReleaseGroup() + ")内");
            }
        }
    }

    /**
     * 普通上架-效验
     * @param param
     * @return
     */
    @Override
    public Map checkedItemCode(Map param) {
        String confirmCode = "";
        WmsWarehouse warehouse = WmsWarehouseHolder.get();
        //解析扫码HU的值
        String scanCodeHuNew = MapUtils.getStringNotNull(param, "scanCodeHu", "HU码为空");
        if(StringUtils.isEmpty(scanCodeHuNew)){
            throw new RfBusinessException("HU码为空！");
        }
        String [] scanCodeHuSplit = new String[0];
        List<WmsTask> wmsTask = null;
        String taskHql = "from WmsTask task where 1 = 1 and ( task.moveTool.palletNo = :scanHu or task.moveTool.outBoxNo = :scanHu or task.moveTool.innerBoxNo = :scanHu or task.moveTool.boxNo = :scanHu ) " +
                "and task.warehouseId = :warehouseId and task.status = 'WK' ";
        if(scanCodeHuNew.contains("^")){
            scanCodeHuSplit = scanCodeHuNew.split("\\^");
            wmsTask = this.commonDao.findByQuery(taskHql, new String[]{"scanHu", "warehouseId"}, new Object[]{scanCodeHuSplit[5], warehouse.getId()});
        }else{
             wmsTask = this.commonDao.findByQuery(taskHql, new String[]{"scanHu", "warehouseId"}, new Object[]{scanCodeHuNew, warehouse.getId()});
        }
        if(wmsTask.size() == 0){
            throw new RfBusinessException("非待上架HU!");
        }

        param.put("flag", "0");
        if(wmsTask.size() != 0){
            if("不合格".equals(wmsTask.get(0).getSku().getInventoryStatus())){
                confirmCode = LocalizedMessage
                        .getLocalizedMessage(confirmCode + "内含不良品（PCS），生成新的并打印HU！\n", UserHolder.getLanguage());
                param.put("flag", "1");
            }

            WmsMoveDoc wmsMoveDoc = this.commonDao.load(WmsMoveDoc.class, wmsTask.get(0).getMoveDocId());
            WmsItem wmsItem = this.commonDao.load(WmsItem.class, wmsTask.get(0).getSku().getItemId());

            if(null != wmsMoveDoc){
                WmsASN wmsASN = (WmsASN)this.commonDao.findByQueryUniqueResult("from WmsASN asn where asn.code = :code", "code", wmsMoveDoc.getRelatedBillCode());
                if(null != wmsASN){
                    //上架时-留样提醒
                    WmsBillType billType = commonDao.load(WmsBillType.class, wmsASN.getBillTypeId());
                    if(WmsBillTypeCode.CGSH.equals(billType.getCode())) {
                        confirmCode = sampleRemind(scanCodeHuNew);
                    }
                    List<WmsASNDetail> asnDetail = this.commonDao.findByQuery("from WmsASNDetail detail where detail.asnId = :asnId " +
                                    "and detail.itemId = :itemId order by (case when detail.urgentState='V_URGENT' then 1 " +
                                    "when detail.urgentState='URGENT' then 2 else 3 end )",
                            new String[]{"asnId", "itemId"}, new Object[]{wmsASN.getId(), wmsItem.getId()});
                    for(WmsASNDetail detail : asnDetail){
                        if("V_URGENT".equals(detail.getUrgentState())){
                            confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "内含特急物料!\n", UserHolder.getLanguage());
                        }else if("URGENT".equals(detail.getUrgentState())){
                            confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "内含加急物料!\n", UserHolder.getLanguage());
                        }
                    }
                }
            }
        }
        if(StringUtils.isNotEmpty(confirmCode)){
            param.put(RfConstant.FORWARD_CONFIRM_CODE, confirmCode);
        }
        return param;
    }



    /**
     * SN上架列表
     * @param param
     * @return
     */
    @Override
    public Map getSnPutAway(Map param) {
        Map result = new HashMap();
        String moveDocId = param.get("moveDocId") == null ? null : param.get("moveDocId").toString();
        WmsMoveDoc moveDoc = this.commonDao.load(WmsMoveDoc.class, Long.parseLong(moveDocId));
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put("moveDocCode", moveDoc.getCode());
        result.putAll(param);
        return result;
    }

    /**
     * SN上架-确认(初始化加载)
     * @param param
     * @return
     */
    @Override
    public Map getSnPutawatConfirm(Map param) {
        Map result = new HashMap();
        result.putAll(param);
        return result;
    }

    /**
     * SN上架-确认按钮
     * @param param
     * @return
     */
    @Override
    public Map findSnPutawayConfirm(Map param) {
        Map result = new HashMap();
        result.putAll(param);

        WmsWarehouse warehouse = WmsWarehouseHolder.get();
        //解析扫二维码的值
        String scanQrCodeNew = param.get("scanQrCode") == null ? "" : param.get("scanQrCode").toString();
        String scanCodePnNew = param.get("scanCodePn") == null ? null : param.get("scanCodePn").toString();//扫pn
        String scanCodeSnNew = param.get("scanCodeSn") == null ? null : param.get("scanCodeSn").toString();//扫sn
        if(StringUtils.isEmpty(scanQrCodeNew)){
            scanCodePnNew= ImgUtils.formatPN2(scanCodePnNew);
            scanCodeSnNew=ImgUtils.formatPN2(scanCodeSnNew);
            String scanCodeSnHis = param.get("scanCodeSnHis") == null ? "" : param.get("scanCodeSnHis").toString();
            if(scanCodeSnHis.contains(scanCodeSnNew)){
                throw new RfBusinessException(scanCodeSnNew + "扫描的SN[" + scanCodeSnNew + "]重复！");
            }
            result.put("scanCodeSnHis", scanCodeSnHis == null ? scanCodeSnNew : scanCodeSnHis + "," + scanCodeSnNew);
        }else{
            String scanQrCodeHis = param.get("scanQrCodeHis") == null ? "" : param.get("scanQrCodeHis").toString();
            if(scanQrCodeHis.contains(scanQrCodeNew)){
                throw new RfBusinessException(scanQrCodeNew + "扫描重复！");
            }
            result.put("scanQrCodeHis", (scanQrCodeHis == null||"".equals(scanQrCodeHis)) ? scanQrCodeNew : scanQrCodeHis + "," + scanQrCodeNew);
        }

        if(StringUtils.isEmpty(scanQrCodeNew) && StringUtils.isEmpty(scanCodeSnNew)){
            throw new RfBusinessException("扫的二维码或者扫码SN不能为空！");
        }

        String scanCodePn = "", scanCodeSn = "";
        String [] scanCodeHuSplit = new String[0];
        // StringBuffer scanCodeSnPnOld = new StringBuffer();
        List<WmsInventory> inventoryList = new ArrayList<>();
        if(StringUtils.isNotEmpty(scanQrCodeNew)){
            if(!scanQrCodeNew.contains(" ")){
                inventoryList = commonDao.findByQuery("from WmsInventory inventory where inventory.moveTool.boxNo = :hu and inventory.qty.baseQty > 0 ", "hu", scanQrCodeNew);
                if(inventoryList.size() == 0){
                    throw new RfBusinessException("扫描的[" + scanQrCodeNew + "]在库存中未找到！");
                }
            }else{
                scanCodeHuSplit = scanQrCodeNew.split("\\ ");
                //[0]-pn or 料号 [1]-sn
                scanCodePn = scanCodeHuSplit[0];
                scanCodeSn = scanCodeHuSplit[1];
            }
        }else{
            scanCodePn = scanCodePnNew;
            scanCodeSn = scanCodeSnNew;
            if(scanCodeSn.equals(scanCodePn)){
                throw new RfBusinessException("SN与PN相同！");
            }
        }
        String locationCode="";
        WmsLocation location = null;
        WmsItem wmsItem = null;
        String receiveLocationCode = param.get("receiveLocationCode") == null ? "" : param.get("receiveLocationCode").toString();
        if(StringUtils.isNotEmpty(scanQrCodeNew)){
            if(!scanQrCodeNew.contains(" ")){
                for(WmsInventory inventory : inventoryList) {
                    List<WmsBox> wmsBoxList = commonDao.findByQuery("from WmsBox box where box.inventoryId = :inventoryId ", "inventoryId", inventory.getId());
                    System.out.println("=============SN的数量：" + wmsBoxList.size() + "==========================");
                    result.put("snCount", wmsBoxList.size());
                    for(WmsBox box : wmsBoxList){
                        scanCodePn = inventory.getPn();
                        scanCodeSn = box.getSnNumber();
                        List<WmsTask> wmsTaskList = commonDao.findByQuery("from WmsTask task where task.id = :taskId and task.status = 'WK' ", "taskId", inventory.getTaskId());
                        if(wmsTaskList.size() == 0){
                            throw new RfBusinessException("非待上架HU!");
                        }
                        wmsItem = commonDao.load(WmsItem.class, wmsTaskList.get(0).getSku().getItemId());
                        /*String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
                        WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{wmsItem.getCode(), scanCodePn});
                        if(!scanCodePn.equals(wmsItem.getCode())){
                            if(null == wmsPnSup){
                                throw new RfBusinessException("PN与料号对应错误！");
                            }
                        }*/

                        WmsLocation locations = null;
                        if(StringUtils.isNotEmpty(receiveLocationCode)){
                            location = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'ST' ", "code", receiveLocationCode);
                            locationCode=locations.getCode();
                        }else {
                            List<Long> loc1 = new ArrayList<Long>();
                            List<Long> loc2 = new ArrayList<Long>();
                            String loc3 = null;
                            String hql = "select DISTINCT location from WmsInventory inventory " +
                                    "left join WmsLocation location on location.id=inventory.locationId " +
                                    "left join WmsStoreArea storeArea on storeArea.id = location.putStoreAreaId " +
                                    "  where inventory.sku.itemId=:itemId  " + //and location.putStoreAreaId=:putStoreAreaId " +
                                    " and location.status = 'E' " +
                                    " AND location.code not in ('JHKW','GYSKW','XNKW') and location.code not like 'LYKW%' " +
                                    " AND location.type='ST' " +
                                    " AND inventory.qty.baseQty>0.0   ORDER BY location.id ";
                            List<WmsLocation> wmsLocationList = this.commonDao.findByQueryMaxNum(hql, new String[]{"itemId"},
                                    new Object[]{inventoryList.get(0).getSku().getItemId()}, 0, 5); //wmsLocation.getPutStoreAreaId()},
                            for (WmsLocation wmsloc : wmsLocationList) {
                                if (wmsloc.getLocationCapacityId() == 5 || wmsloc.getLocationCapacityId() == 6) {
                                    if (wmsloc.getLocationCapacityId() == 6 && wmsloc.getTouchTimes() > 0) {
                                        loc1.add(wmsloc.getId());
                                    } else if (wmsloc.getLocationCapacityId() == 5 && wmsloc.getTouchTimes() > 0) {
                                        loc2.add(wmsloc.getId());
                                    }
                                } else {
                                    List<String> l = (List<String>) this.commonDao.findByQuery("SELECT DISTINCT location.code from WmsLocation location left join WmsLocationCapacity cap on cap.id=location.locationCapacityId where location.status = 'E' and  location.type = 'ST' and cap.id=5 and location.touchTimes=0 ");
                                    loc3 = l.get(0);
                                }
                            }

                            if (loc1.size() != 0) {
                                for (int i = 0; loc1.size() > i; i++) {
                                    Long log = (Long) commonDao.findByQueryUniqueResult("SELECT locationId from WmsInventoryLog l  WHERE l.id =( SELECT max(id) from WmsInventoryLog g WHERE g.locationId = :loc )", "loc", Long.valueOf(loc1.get(i)));
                                    String l = (String) this.commonDao.findByQueryUniqueResult("SELECT DISTINCT location.code from WmsLocation location where location.id=:id ", "id", log);
                                    locationCode = l;
                                }
                            } else if (loc2.size() != 0 && locationCode == "") {
                                for (int i = 0; loc2.size() > i; i++) {
                                    Long log = (Long) commonDao.findByQueryUniqueResult("SELECT locationId from WmsInventoryLog l  WHERE l.id =( SELECT max(id) from WmsInventoryLog g WHERE g.locationId = :loc )", "loc", Long.valueOf(loc2.get(i)));
                                    String l = (String) this.commonDao.findByQueryUniqueResult("SELECT DISTINCT location.code from WmsLocation location where location.id=:id ", "id", log);
                                    locationCode = l;
                                }
                            } else if ((loc3 != null && locationCode == "")) {
                                locationCode = loc3;
                            }
                        }

                        String allSn = result.get("allSn") == null ? "" : result.get("allSn").toString();
                        String snList = result.get("snList") == null ? null : result.get("snList").toString();
                        String pnList = result.get("pnList") == null ? null : result.get("pnList").toString();
                        if(StringUtils.isNotEmpty(pnList)){
                            if(!pnList.contains(scanCodePn)){
                                //throw new RfBusinessException("PN错误！");
                            }
                        }

                        result.put("allSn", allSn + "\n" + scanCodeSn);
                        result.put("snList", snList == null ? scanCodeSn + "|" + scanCodePn : snList + "," +  scanCodeSn + "|" + scanCodePn);
                        result.put("pnList", pnList == null ? scanCodePn : snList + "," +  scanCodePn);
                    }
                }

                WmsItemKey itemKey = commonDao.load(WmsItemKey.class, inventoryList.get(0).getSku().getItemKeyId());
                WmsErpWh wh = commonDao.load(WmsErpWh.class, itemKey.getLotInfo().getErpId());
                if(null != wh){
                    result.put("factoryId", wh.getCode());//工厂
                    result.put("inventoryPlace", wh.getInventoryPlace());//库存地点
                }
            }else{
                String hql = "select inventory from WmsInventory inventory left join WmsBox box on box.inventoryId = inventory.id where inventory.pn = :pn and box.snNumber = :sn and box.status != 'S' ";
                WmsInventory wmsInventory = (WmsInventory) commonDao.findByQueryUniqueResult(hql, new String[]{"pn", "sn"}, new Object[]{scanCodePn, scanCodeSn});
                List<WmsTask> wmsTaskList = commonDao.findByQuery("from WmsTask task where task.id = :taskId and task.status = 'WK' ", "taskId", wmsInventory.getTaskId());
                if(wmsTaskList.size() == 0){
                    throw new RfBusinessException("非待上架HU!");
                }
                wmsItem = commonDao.load(WmsItem.class, wmsTaskList.get(0).getSku().getItemId());
                String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
                WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{wmsItem.getCode(), scanCodePn});
                if(!scanCodePn.equals(wmsItem.getCode())){
                    if(null == wmsPnSup){
                        throw new RfBusinessException("PN与料号对应错误！");
                    }
                }
                /*String findSnTr = "select st from WmsSnTraceability st where st.status = 'E' and st.serialNo=:sn AND st.itemId = (SELECT max(ps.itemId) FROM WmsPnSup ps left join WmsItem i on i.id = ps.itemId where i.code =:pn or ps.pn = :pn)";
                List<WmsSnTraceability> snTraceabilityList = commonDao.findByQuery(findSnTr,new String[]{"sn","pn","pn"},new Object[]{scanCodeSn, scanCodePn, scanCodePn});
                if (snTraceabilityList.size() <1){
                    throw new RfBusinessException("该SN未在SN溯源记录表中维护！");
                }*/

                if(StringUtils.isNotEmpty(receiveLocationCode)){
                    location = (WmsLocation) this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'ST' ", "code", receiveLocationCode);
                }else{
                    location = this.commonDao.load(WmsLocation.class, wmsTaskList.get(0).getToLocId());
                }
                if(null == location){
                    throw new RfBusinessException("扫描的收货库位在系统中未维护！");
                }

                String allSn = result.get("allSn") == null ? "" : result.get("allSn").toString();
                String snList = result.get("snList") == null ? null : result.get("snList").toString();
                String pnList = result.get("pnList") == null ? null : result.get("pnList").toString();
                if(StringUtils.isNotEmpty(pnList)){
                    if(!pnList.contains(scanCodePn)){
                        throw new RfBusinessException("PN错误！");
                    }
                }

                WmsItemKey itemKey = commonDao.load(WmsItemKey.class, wmsInventory.getSku().getItemKeyId());
                WmsErpWh wh = commonDao.load(WmsErpWh.class, itemKey.getLotInfo().getErpId());
                if(null != wh){
                    result.put("factoryId", wh.getCode());//工厂
                    result.put("inventoryPlace", wh.getInventoryPlace());//库存地点
                }

                result.put("allSn", allSn + "\n" + scanCodeSn);
                result.put("snList", snList == null ? scanCodeSn + "|" + scanCodePn : snList + "," +  scanCodeSn + "|" + scanCodePn);
                result.put("pnList", pnList == null ? scanCodePn : snList + "," +  scanCodePn);
            }
        }else{
            String hql = "select inventory from WmsInventory inventory left join WmsBox box on box.inventoryId = inventory.id where inventory.pn = :pn and box.snNumber = :sn and box.status != 'S' ";
            WmsInventory wmsInventory = (WmsInventory) commonDao.findByQueryUniqueResult(hql, new String[]{"pn", "sn"}, new Object[]{scanCodePn, scanCodeSn});
            List<WmsTask> wmsTaskList = commonDao.findByQuery("from WmsTask task where task.id = :taskId and task.status = 'WK' ", "taskId", wmsInventory.getTaskId());
            if(wmsTaskList.size() == 0){
                throw new RfBusinessException("非待上架HU!");
            }
            wmsItem = commonDao.load(WmsItem.class, wmsTaskList.get(0).getSku().getItemId());
            String pnItemHql = "select sup from WmsPnSup sup left join WmsItem item on item.id = sup.itemId where 1 = 1 and item.code = :code and sup.pn = :pn and sup.status = 'E' ";
            WmsPnSup wmsPnSup = (WmsPnSup)this.commonDao.findByQueryUniqueResult(pnItemHql, new String[]{"code", "pn"}, new Object[]{wmsItem.getCode(), scanCodePn});
            if(!scanCodePn.equals(wmsItem.getCode())){
                if(null == wmsPnSup){
                    throw new RfBusinessException("PN与料号对应错误！");
                }
            }
            /*String findSnTr = "select st from WmsSnTraceability st where st.serialNo=:sn AND st.status = 'E' and st.itemId = (SELECT max(ps.itemId) FROM WmsPnSup ps left join WmsItem i on i.id = ps.itemId where i.code =:pn or ps.pn = :pn)";
            List<WmsSnTraceability> snTraceabilityList = commonDao.findByQuery(findSnTr,new String[]{"sn","pn","pn"},new Object[]{scanCodeSn, scanCodePn, scanCodePn});
            if (snTraceabilityList.size() <1){
                throw new RfBusinessException("该SN未在SN溯源记录表中维护！");
            }*/
            WmsLocation locations = null;
            if(StringUtils.isNotEmpty(receiveLocationCode)){
                location = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'ST' ", "code", receiveLocationCode);
                locationCode=locations.getCode();
            }else {
                List<Long> loc1 = new ArrayList<Long>();
                List<Long> loc2 = new ArrayList<Long>();
                String loc3 = null;
                String hql1 = "select DISTINCT location from WmsInventory inventory " +
                        "left join WmsLocation location on location.id=inventory.locationId " +
                        "left join WmsStoreArea storeArea on storeArea.id = location.putStoreAreaId " +
                        "  where inventory.sku.itemId=:itemId  " + //and location.putStoreAreaId=:putStoreAreaId " +
                        " and location.status = 'E' " +
                        " AND location.code not in ('JHKW','GYSKW','XNKW') and location.code not like 'LYKW%' " +
                        " AND location.type='ST' " +
                        " AND inventory.qty.baseQty>0.0   ORDER BY location.id ";
                List<WmsLocation> wmsLocationList = this.commonDao.findByQueryMaxNum(hql1, new String[]{"itemId"},
                        new Object[]{wmsInventory.getSku().getItemId()}, 0, 5); //wmsLocation.getPutStoreAreaId()},
                for (WmsLocation wmsloc : wmsLocationList) {
                    if (wmsloc.getLocationCapacityId() == 5 || wmsloc.getLocationCapacityId() == 6) {
                        if (wmsloc.getLocationCapacityId() == 6 && wmsloc.getTouchTimes() > 0) {
                            loc1.add(wmsloc.getId());
                        } else if (wmsloc.getLocationCapacityId() == 5 && wmsloc.getTouchTimes() > 0) {
                            loc2.add(wmsloc.getId());
                        }
                    } else {
                        List<String> l = (List<String>) this.commonDao.findByQuery("SELECT DISTINCT location.code from WmsLocation location left join WmsLocationCapacity cap on cap.id=location.locationCapacityId where location.status = 'E' and  location.type = 'ST' and cap.id=5 and location.touchTimes=0 ");
                        loc3 = l.get(0);
                    }
                }

                if (loc1.size() != 0) {
                    for (int i = 0; loc1.size() > i; i++) {
                        Long log = (Long) commonDao.findByQueryUniqueResult("SELECT locationId from WmsInventoryLog l  WHERE l.id =( SELECT max(id) from WmsInventoryLog g WHERE g.locationId = :loc )", "loc", Long.valueOf(loc1.get(i)));
                        String l = (String) this.commonDao.findByQueryUniqueResult("SELECT DISTINCT location.code from WmsLocation location where location.id=:id ", "id", log);
                        locationCode = l;
                    }
                } else if (loc2.size() != 0 && locationCode == "") {
                    for (int i = 0; loc2.size() > i; i++) {
                        Long log = (Long) commonDao.findByQueryUniqueResult("SELECT locationId from WmsInventoryLog l  WHERE l.id =( SELECT max(id) from WmsInventoryLog g WHERE g.locationId = :loc )", "loc", Long.valueOf(loc2.get(i)));
                        String l = (String) this.commonDao.findByQueryUniqueResult("SELECT DISTINCT location.code from WmsLocation location where location.id=:id ", "id", log);
                        locationCode = l;
                    }
                } else if ((loc3 != null && locationCode == "")) {
                    locationCode = loc3;
                }
            }

            String allSn = result.get("allSn") == null ? "" : result.get("allSn").toString();
            String snList = result.get("snList") == null ? null : result.get("snList").toString();
            String pnList = result.get("pnList") == null ? null : result.get("pnList").toString();
            if(StringUtils.isNotEmpty(pnList)){
                if(!pnList.contains(scanCodePn)){
                    throw new RfBusinessException("PN错误！");
                }
            }

            result.put("allSn", allSn + "\n" + scanCodeSn);
            result.put("snList", snList == null ? scanCodeSn + "|" + scanCodePn : snList + "," +  scanCodeSn + "|" + scanCodePn);
            result.put("pnList", pnList == null ? scanCodePn : snList + "," +  scanCodePn);
        }

        if(scanCodePn.equals(scanCodeSn)){
            throw new RfBusinessException("PN与SN相同！");
        }

        int scanCount = Integer.valueOf(param.get("scanCount") == null ? "0" : param.get("scanCount").toString()) + 1;

        result.put("scanCount", scanCount);//扫描次数
        result.put("itemCode", wmsItem.getCode());
        result.put("toLocationCode", locationCode);//推荐库位
        result.put("receiveLocationCode", receiveLocationCode);//确认库位
        result.put("libraryFull", param.get("libraryFull").toString());
        result.put("scanCodeSn", "");//清除SN
        result.put("scanCodePn", "");//清除PN
        result.put("scanQrCode", "");//清除二维码
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    /**
     * 按上架单散货上架-开始上架（SN上架）
     *
     * @param param
     * @return
     */
    @Override
    @Transactional
    public Map beginSNPutawayForMoveDocPut(Map param) {
        Map result = new HashMap();
       // WmsWarehouse warehouse = WmsWarehouseHolder.get();
        WmsWorker worker = WmsWorkerHolder.get();

        String receiveLocationCode = param.get("receiveLocationCode") == null ? "" : param.get("receiveLocationCode").toString();
        boolean beCheck=false;//如果是输入上架库位，那我就验证上架库位和任务明细是否是同一混放分组，不是就报错
        if(StringUtils.isNotEmpty(receiveLocationCode)){
            beCheck=true;
            receiveLocationCode = param.get("receiveLocationCode").toString();
        }else{
            receiveLocationCode = param.get("toLocationCode").toString();
        }

        WmsLocation location = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :code and location.status = 'E' and  location.type = 'ST' ", "code", receiveLocationCode);
        if(null == location){
            throw new RfBusinessException("扫描的收货库位在系统中未维护！");
        }
        //判断是否为高架库位
        WmsLocationCapacity locationCapacity = this.commonDao.load(WmsLocationCapacity.class, location.getLocationCapacityId());
        if("G".equals(locationCapacity.getBearingType())){
            if(StringUtils.isEmpty(param.get("libraryFull").toString())){
                throw new RfBusinessException("扫描的库位为高位货架库位,库满度不能为空！");
            }
        }

        String scanQrCodeHis = param.get("scanQrCodeHis") == null ? "" : param.get("scanQrCodeHis").toString();
        if(StringUtils.isNotEmpty(scanQrCodeHis) && !scanQrCodeHis.contains(" ")){
            String [] huList = scanQrCodeHis.split("\\,");
            long beginTime = System.currentTimeMillis();
            for(String hu : huList){
                getMoveDocConfirm(hu, beCheck, location);
                logger.info("耗时：" + (System.currentTimeMillis() - beginTime));
            }
        }else{
            String snSplits = param.get("snList").toString();
            String [] snList = snSplits.split("\\,");
            for(int i = 0; i < snList.length; i++){
                String [] snAndPn = snList[i].split("\\|");
                String hql = "select inventory from WmsInventory inventory left join WmsBox box on box.inventoryId = inventory.id where inventory.pn = :pn and box.snNumber = :snNumber and box.status != 'S' ";
                WmsInventory wmsInventory = (WmsInventory) commonDao.findByQueryUniqueResult(hql, new String[]{"pn", "snNumber"}, new Object[]{snAndPn[1], snAndPn[0]});
                WmsTask wmsTask = (WmsTask) commonDao.findByQueryUniqueResult("from WmsTask task where task.id = :taskId and task.status = 'WK' ", "taskId", wmsInventory.getTaskId());
                if(null == wmsTask){
                    throw new RfBusinessException("非待上架HU!");
                }
                WmsLocation location1=this.commonDao.get(WmsLocation.class,wmsTask.getToLocId());
                if(beCheck&&!"XNKW".equals(location1.getCode())){//验证混放分组是否一致
                    checkReleaseGroup(location,wmsInventory);
                }
                WmsMoveDoc moveDoc = commonDao.load(WmsMoveDoc.class, wmsTask.getMoveDocId());
                logger.info("操作人[" + UserHolder.getUser().getName() + "],库存ID[" + wmsInventory.getId() + "],上架作业ID[" + moveDoc.getId() + "],作业类型[" + moveDoc.getType() + "],上架任务ID[" + wmsTask.getId() + "]");
                if(WmsMoveDocType.PU.equals(moveDoc.getType())){
                    wmsMoveDocPUManager.singleMoveDocConfirm(wmsTask, location.getId(), 1D, worker.getId(), snAndPn[0]);
                }else{
                    throw new RfBusinessException("无上架任务，不允许上架操作！");
                }
            }
        }
        //库满度存进库位
        String libraryFull = param.get("libraryFull") == null ? "" : param.get("libraryFull").toString();
        if(StringUtils.isNotEmpty(libraryFull)){
            location.setLibraryFull(libraryFull);
            commonDao.store(location);
        }
        //if(1==1){throw new RfBusinessException("test");}
        //beginPutawayWithoutTakeForMoveDocPut(param);
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put(RfConstant.CLEAR_VALUE, true);
        return result;
    }

    public void getMoveDocConfirm(String hu, Boolean beCheck, WmsLocation location){
        String hql = "select inventory from WmsInventory inventory where inventory.moveTool.boxNo = :boxNo and inventory.qty.baseQty>0 ";
        WmsInventory wmsInventory = (WmsInventory) commonDao.findByQueryUniqueResult(hql, "boxNo", hu);
        List<WmsBox> boxList = commonDao.findByQuery("from WmsBox box where box.inventoryId = :inventoryId", "inventoryId", wmsInventory.getId());
        WmsTask wmsTask = (WmsTask) commonDao.findByQueryUniqueResult("from WmsTask task where task.id = :taskId and task.status = 'WK' ", "taskId", wmsInventory.getTaskId());
        if(null == wmsTask){
            throw new RfBusinessException("非待上架HU!");
        }
        WmsLocation location1=this.commonDao.get(WmsLocation.class,wmsTask.getToLocId());
        if(beCheck&&!"XNKW".equals(location1.getCode())){//验证混放分组是否一致
            checkReleaseGroup(location,wmsInventory);
        }
        WmsMoveDoc moveDoc = commonDao.load(WmsMoveDoc.class, wmsTask.getMoveDocId());
        logger.info("操作人[" + UserHolder.getUser().getName() + "],库存ID[" + wmsInventory.getId() + "],上架作业ID[" + moveDoc.getId() + "],作业类型[" + moveDoc.getType() + "],上架任务ID[" + wmsTask.getId() + "]");
        // 上架数量
        Double putawayQty = WmsPackageUnitUtils
                .getQtyBU(wmsTask.getQty().getConvertFigure(), Double.valueOf(boxList.size()), wmsTask.getQty().getBuPrecision());
        if(WmsMoveDocType.PU.equals(moveDoc.getType())){
            // 上架确认库存
            WmsInventoryPlusDto wmsInventoryPlusDto = WmsInventoryPlusDtoFactory
                    .createPutawayConfirmInventory(wmsTask, location.getId(), Double.valueOf(boxList.size()), putawayQty);
            // 上架库存日志记录相关单号
            if (WmsMoveDocType.PU.equals(moveDoc.getType())) {
                WmsASN wmsASN = commonDao.load(WmsASN.class, wmsTask.getRelatedObjId());
                wmsInventoryPlusDto.setRelatedBillCode(wmsASN.getCode());
            }
            wmsInventoryPlusDto.getMoveTool().setBoxNo(wmsTask.getMoveTool().getBoxNo());
            wmsInventoryPlusDto.setSapBatch(wmsTask.getSapBatch());
            wmsInventoryPlusManager.confirm(wmsInventoryPlusDto);
        }else{
            throw new RfBusinessException("无上架任务，不允许上架操作！");
        }
        if (DoubleUtils.sub(wmsTask.getAllocateQty(), putawayQty, wmsTask.getQty().getBuPrecision()) == 0D) {
            // 全部上架
            wmsTask.addPutawayQty(putawayQty, wmsTask.getQty().getBuPrecision());
            if (Objects.isNull(wmsTask.getStartTime())) {
                wmsTask.setStartTime(new Date());
            }
            if (WmsMoveDocType.KP.equals(wmsTask.getType())){
                wmsTask.addPickQty(putawayQty, wmsTask.getQty().getBuPrecision());
            }
            wmsTask.setStatus(WmsTaskStatus.WF);
            wmsTask.setEndTime(new Date());
        }
        commonDao.store(wmsTask);
        // 回写ASN数据
        workflowManager.sendMessage(wmsTask.getRelatedObjId(), WmsMoveDocAsynManager.DO_REFRESH_ASN_DETAIL,
                ThornTask.M_PRIORITY, ThornTask.DEF_REPEAT_COUNT);
        // 异步刷新moveDoc作业状态
        workflowManager.sendMessage(wmsTask.getMoveDocId(), WmsMoveDocAsynManager.DO_REFRESH_MOVE_DOC_PUTAWAY_STATUS,
                ThornTask.M_PRIORITY, ThornTask.DEF_REPEAT_COUNT);
    }

    /**
     * 送检上架-初始化
     * @param params
     * @return
     */
    @Override
    public Map qualityPutaway(Map params) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("insDetailId", params.get("insDetailId"));
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    /**
     * 送检上架-信息加载
     */
    @Override
    public Map getQualityPutawatConfirm(Map params) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.putAll(params);
        /*String hu = (String) params.get("huCode");
        String huCodeHistory = (String) params.get("huCodeHistory");
        if(StringUtils.isNotEmpty(hu)){
            List<WmsQualityInspectionDetail> wmsQualityInspectionDetailList = commonDao.findByQuery("from WmsQualityInspectionDetail detail where ( detail.hu = :hu or detail.palletNo = :palletNo or detail.outBoxNo = :outBoxNo or detail.innerBoxNo = :innerBoxNo ) ",
                    new String[]{"hu","palletNo","outBoxNo","innerBoxNo"}, new Object[]{hu,hu,hu,hu});
            if(wmsQualityInspectionDetailList.size() == 0){
                throw new RfBusinessException("扫描的HU[" + hu + "]在质检管理中未找到！");
            }else{
                for(WmsQualityInspectionDetail detail : wmsQualityInspectionDetailList){
                    String scanCount = result.get("scanCount") == null ? "" : result.get("scanCount").toString();
                    result.put("scanCount", scanCount + detail.getPlanQty() + ",");//计划质检数量

                    WmsItem wmsItem = this.commonDao.load(WmsItem.class, detail.getItemId());//根据itemKey获取货品
                    if(wmsItem.getQuaWorkAreaId() == null){
                        throw new RfBusinessException(wmsItem.getCode() + "未维护质检区域！");
                    }
                    WmsWorkArea workArea = this.commonDao.load(WmsWorkArea.class, wmsItem.getQuaWorkAreaId());//根据货品中的质检区域获取工作区
                    String qualityArea = result.get("qualityArea") == null ? "" : result.get("qualityArea").toString();
                    String itemCode = result.get("itemCode") == null ? "" : result.get("itemCode").toString();
                    result.put("qualityArea", qualityArea + workArea.getDescription() + ",");//质检区域==工作区描述
                    result.put("itemCode", itemCode + wmsItem.getCode() + "," );
                }

            }

            WmsQualityInspection wmsQualityInspection = this.commonDao.load(WmsQualityInspection.class, wmsQualityInspectionDetailList.get(0).getQualityId());
            result.put("qualityCode", wmsQualityInspection.getQualityCode());//送检单号
        }

        result.put("huCode", huCodeHistory);*/
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    /**
     * 送检上架-确认
     */
    @Override
    @Transactional
    public Map qualityPutawatConfirm(Map params) {
        Map<String, Object> result = new HashMap<String, Object>();
        String scanCodeHu = params.get("scanCodeHu") == null ? null : params.get("scanCodeHu").toString();//扫描HU
        String hu = null;
        if (!scanCodeHu.contains("^")) {
            hu = scanCodeHu;
        } else {
            String[] scanCodeHus = scanCodeHu.split("\\^");
            hu = scanCodeHus[5];
        }
        if(StringUtils.isNotEmpty(hu)){
            List<WmsQualityInspectionDetail> wmsQualityInspectionDetailList = commonDao.findByQuery("from WmsQualityInspectionDetail detail where ( detail.hu = :hu or detail.palletNo = :palletNo or detail.outBoxNo = :outBoxNo or detail.innerBoxNo = :innerBoxNo ) and detail.status != 'W' ",
                    new String[]{"hu","palletNo","outBoxNo","innerBoxNo"}, new Object[]{hu,hu,hu,hu});
            if(wmsQualityInspectionDetailList.size() == 0){
                throw new RfBusinessException("扫描的HU[" + hu + "]在质检管理中未找到！");
            }else{
                for(WmsQualityInspectionDetail detail : wmsQualityInspectionDetailList){
                    String scanCount = params.get("scanCount") == null ? "" : params.get("scanCount").toString();
                    params.put("scanCount", scanCount + detail.getPlanQty() + ",");//计划质检数量

                    WmsItem wmsItem = this.commonDao.load(WmsItem.class, detail.getItemId());//根据itemKey获取货品
                    if(wmsItem.getQuaWorkAreaId() == null){
                        throw new RfBusinessException(wmsItem.getCode() + "未维护质检区域！");
                    }
                    WmsWorkArea workArea = this.commonDao.load(WmsWorkArea.class, wmsItem.getQuaWorkAreaId());//根据货品中的质检区域获取工作区
                    String qualityArea = params.get("qualityArea") == null ? "" : params.get("qualityArea").toString();
                    String itemCode = params.get("itemCode") == null ? "" : params.get("itemCode").toString();
                    params.put("qualityArea", qualityArea + workArea.getDescription() + ",");//质检区域==工作区描述
                    params.put("itemCode", itemCode + wmsItem.getCode() + "," );
                }

            }

            WmsQualityInspection wmsQualityInspection = this.commonDao.load(WmsQualityInspection.class, wmsQualityInspectionDetailList.get(0).getQualityId());
            params.put("qualityCode", wmsQualityInspection.getQualityCode());//送检单号
        }
        String huCodeHistory = params.get("huCodeHistory") == null ? "" : params.get("huCodeHistory").toString();
        result.putAll(params);
        result.put("scanCodeHu", "");//清除hu
        result.put("huCodeHistory", huCodeHistory + hu + ",");
        result.put("huCode", hu);
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    /**
     * 送检任务上架-推送至SFC
     */
    @Override
    @Transactional
    public Map qualityConfirm(Map params) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> result = new HashMap<String, Object>();
        WmsWarehouse warehouse = WmsWarehouseHolder.get();
        String huCode = params.get("huCodeHistory") == null ? null : params.get("huCodeHistory").toString();//扫描HU
        String scanCodeLocation = MapUtils.getStringNotNull(params, "scanCodeLocation", "库位为空");//扫描库位
        logger.info("送检任务上架-推送至SFC时RF扫描的库位[" + scanCodeLocation + "]");
        logger.info("送检任务上架-推送至SFC时RF扫描的HU[" + huCode + "]");
        WmsLocation wmsLocation = (WmsLocation)this.commonDao.findByQueryUniqueResult("from WmsLocation location where location.code = :locationCode and location.status = 'E' and location.warehouseId = :warehouseId ",
                new String[]{"locationCode", "warehouseId"}, new Object[]{scanCodeLocation, warehouse.getId()});
        if(null == wmsLocation){
            throw new RfBusinessException("库位代码没有维护！");
        }
        //质检信息推送至SFC
        List<WmsQualityInspection> wmsQualityInspectionList = new ArrayList<>();
        WmsQualityInspection wmsQualityInspection = EntityFactory.getEntity(WmsQualityInspection.class);
        String [] hus = huCode.split("\\,");
        List<String> stringList = Arrays.asList(hus);
        List<String> huList = stringList.stream().filter( s -> !"".equals(s)
        ).collect(Collectors.toList());
        logger.info("送检任务上架-hus:"+ huList.toString());
        for(String hu : huList){
            // 库存信息
            String hql5 = "FROM WmsInventory inventory WHERE 1 = 1 and ( inventory.moveTool.boxNo = :scanHu or inventory.moveTool.palletNo = :scanHu or inventory.moveTool.innerBoxNo = :scanHu or inventory.moveTool.outBoxNo = :scanHu )";
            List<WmsInventory> inventoryList= this.commonDao.findByQuery(hql5, new String[]{"scanHu", "scanHu", "scanHu", "scanHu"}, new Object[]{hu, hu, hu, hu});
            logger.info("送检任务上架-查询需送检库存sql:"+hql5+"hu值"+hu);

            //无计划移位
            for(WmsInventory inventory : inventoryList){
                logger.info("送检任务上架-推送至SFC时原始库存-库位=" + inventory.getLocationId() + ",库存id=" + inventory.getId() + "," + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
                logger.info("location——qualityConfirm:库存id"+inventory.getId()+"的原库位id为："+inventory.getLocationId());
                inventory.setLocationId(wmsLocation.getId());
                Sku sku=inventory.getSku();
                sku.setInventoryStatus("待检");
                inventory.setSku(sku);
                logger.info("location——qualityConfirm:库存id"+inventory.getId()+"的新库位id为："+inventory.getLocationId());
                commonDao.store(inventory);
                logger.info("送检任务上架-推送至SFC时更新库存-库位=" + inventory.getLocationId() + ",库存id=" + inventory.getId() + "," + DateUtils.format(new Date(),"yyyy-MM-dd HH:mm:ss:SSS"));
            }

            List<WmsQualityInspectionDetail> wmsQualityInspectionDetailList = commonDao.findByQuery("from WmsQualityInspectionDetail detail where ( detail.hu = :hu or detail.palletNo = :palletNo or detail.outBoxNo = :outBoxNo or detail.innerBoxNo = :innerBoxNo )  and detail.status != 'W' ",
                    new String[]{"hu","palletNo","outBoxNo","innerBoxNo"}, new Object[]{hu,hu,hu,hu});
            logger.info("送检任务上架-wmsQualityInspectionDetailList"+wmsQualityInspectionDetailList.size());
            //质检单的质检库位更新为扫描的库位
            for(WmsQualityInspectionDetail detail : wmsQualityInspectionDetailList){
                detail.setLocationId(wmsLocation.getId());
                commonDao.store(detail);
                logger.info("送检任务上架-质检单id:"+detail.getQualityId()+"质检单明细id:"+detail.getId());
            }
            if(wmsQualityInspectionDetailList.size() == 0){
                throw new RfBusinessException("扫描的HU[" + hu + "]在质检管理中未找到！");
            }
            wmsQualityInspection = commonDao.load(WmsQualityInspection.class, wmsQualityInspectionDetailList.get(0).getQualityId());

        }

        //10.10.8.163放开这段代码
        /*if(1==1){
            throw new RfBusinessException("10.10.8.163 不允许推送至SFC");
        }*/

        wmsQualityInspectionList.add(wmsQualityInspection);
        logger.info("质检单号：" + wmsQualityInspection.getCode() + "进行直接推送SFC开始..." + sdf.format(new Date()));
        wmsQualityInspectionManager.getWMSInspectionSheet(wmsQualityInspectionList, huCode);
        logger.info("质检单号：" + wmsQualityInspection.getCode() + "进行直接推送SFC结束..." + sdf.format(new Date()));
        result.put(RfConstant.CLEAR_VALUE, Boolean.TRUE);
        result.put(RfConstant.FORWARD_VALUE, "success");
        return result;
    }

    /**
     * 返回
     */
    @Override
    public Map goBack(Map params) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put(RfConstant.FORWARD_VALUE, "success");
        result.put(RfConstant.CLEAR_VALUE, Boolean.TRUE);
        return result;
    }

    @Override
    public Map checkItemNo(Map params) {
        Map<String, Object> result = new HashMap<String, Object>();
        String scanCodeHu = params.get("scanCodeHu") == null ? null : params.get("scanCodeHu").toString();//扫描HU
        String hu = null;
        if (!scanCodeHu.contains("^")) {
            hu = scanCodeHu;
        } else {
            String[] scanCodeHus = scanCodeHu.split("\\^");
            hu = scanCodeHus[5];
        }
        List<WmsQualityInspectionDetail> wmsQualityInspectionDetailList = commonDao.findByQuery("from WmsQualityInspectionDetail detail where ( detail.hu = :hu or detail.palletNo = :hu or detail.outBoxNo = :hu or detail.innerBoxNo = :hu ) ",
                new String[]{"hu","hu","hu","hu"}, new Object[]{hu,hu,hu,hu});
        for(WmsQualityInspectionDetail detail : wmsQualityInspectionDetailList){
            WmsItem wmsItem = commonDao.load(WmsItem.class, detail.getItemId());
            if(wmsItem.getBeWarehouseQua()){
                if(null != wmsItem.getQuaWorkAreaId()){
                    WmsWorkArea workArea = this.commonDao.load(WmsWorkArea.class, wmsItem.getQuaWorkAreaId());
                    params.put("forwardConfirmCode", "该货品需要入库质检，质检区域为：" + workArea.getDescription());
                }else{
                    params.put("forwardConfirmCode", "该货品需要入库质检，质检区域为：" + wmsItem.getQuaWorkAreaId());
                }
            }
        }
        return result;
    }

    public void operation(String scanCodeSns, String scanCodePns, WmsLocation wmsLocation){
        /*String invhql=" SELECT inv.id " +
                "FROM WmsInventory inv, WmsBox box,WmsBoxContent boxContent " +
                "where inv.locationId=box.locationId and boxContent.boxId = box.id " +
                "and inv.pn=:pn and inv.sku.itemKeyId= boxContent.itemKeyId and box.snNumber=:snNumber order by inv.id desc";*/
        String invhql = "select inventory.id from WmsInventory inventory left join WmsBox box on box.inventoryId = inventory.id where box.snNumber = :snNumber";
        List<Long> inventoryList =   commonDao.findByQuery(invhql, "snNumber", scanCodeSns);
        //修改库存！
        for(Long invId : inventoryList){
            WmsInventory outInventory = commonDao.load(WmsInventory.class, invId);
            WmsInventoryPlusDto outDto = new WmsInventoryPlusDto();
            outDto.setLockStatus(outInventory.getBeLockStatus());
            outDto.setBeCountLock(outInventory.getBeCountLock());
            outDto.setLocationId(outInventory.getLocationId());
            outDto.getQty().setBaseQty(-1d);
            outDto.getQty().setPackQty(-1d);
            wmsInventoryPlusManager.out(outInventory, 1d, outDto);
            WmsInventoryPlusDto inDto = WmsInventoryPlusDtoFactory.copyFromInventory(outInventory);
            inDto.setTaskId((Long)null);
            inDto.setWorkerId((Long)null);
            inDto.setLocationId(wmsLocation.getId());
            inDto.setPn(outInventory.getPn());//pn
            inDto.setPurchaseOrderNo(outInventory.getPurchaseOrderNo());//工单号
            inDto.setInInventoryPlaceDate(outInventory.getInInventoryPlaceDate());//入库存地点日期
            WmsInventory newInventory = wmsInventoryPlusManager.in(inDto);
            List<WmsBox> boxList = commonDao.findByQuery("from WmsBox box where box.snNumber = :snNumber ", "snNumber", invId);
            if(boxList.size() > 0){
                //无计划移位
                //修改box表location
                for(WmsBox box : boxList){
                    box.setInventoryId(newInventory.getId());
                    //box.setLocationId(wmsLocation.getId()); //废弃，使用库存里的库位
                    commonDao.store(box);
                }
            }
        }
    }

    public String sampleRemind(String huCode){
        String confirmCode = "";
        //通过hu查询库存的库存状态
        String [] scanCodeHuSplit = null;
        List<WmsInventory> inventoryList = null;
        if(huCode.contains("^")){
            scanCodeHuSplit = huCode.split("\\^");
            inventoryList = this.commonDao.findByQuery("from WmsInventory inv where inv.moveTool.palletNo = :hu or inv.moveTool.outBoxNo = :hu or inv.moveTool.innerBoxNo = :hu " +
                    "or inv.moveTool.boxNo = :hu ", "hu", scanCodeHuSplit[5]);
        }else{
            inventoryList = this.commonDao.findByQuery("from WmsInventory inv where inv.moveTool.palletNo = :hu or inv.moveTool.outBoxNo = :hu or inv.moveTool.innerBoxNo = :hu " +
                    "or inv.moveTool.boxNo = :hu ", "hu", huCode);
        }

        for(WmsInventory inventory : inventoryList){
            WmsItem wmsItem = commonDao.load(WmsItem.class, inventory.getSku().getItemId());
            WmsItemKey wmsItemKey = commonDao.load(WmsItemKey.class, inventory.getSku().getItemKeyId());
            WmsSupplier wmsSupplier = commonDao.load(WmsSupplier.class, wmsItemKey.getLotInfo().getSupplierId());
            logger.info("货品id[" + wmsItem.getId() + "],货品编码[" + wmsItem.getCode() + "],供应商编码[" + wmsSupplier.getCode() + "],供应商名称[" + wmsSupplier.getName() + "]");
            if(null != wmsSupplier){
                if(wmsItem.getBeROHS()){
                    String hql = "from WmsInspectionRecord record where record.itemId = :itemId and record.supplierId = :supplierId and record.rohsDate is not null order by record.rohsDate desc ";
                    String hql1 = "select sample from WmsCycleSample sample left join WmsSupplier supplier on supplier.id = sample.cycleSupplierId where supplier.code = :supplierCode " +
                            "and sample.cycleItemId = :itemId and sample.rohsCycle is not null and sample.status = 'E' ";
                    WmsCycleSample sample = (WmsCycleSample) commonDao.findByQueryUniqueResult(hql1, new String[]{"supplierCode", "itemId"}, new Object[]{wmsSupplier.getCode(), wmsItem.getId()});
                    List<WmsInspectionRecord> recordList = commonDao.findByQuery(hql, new String[]{"itemId", "supplierId"}, new Object[]{wmsItem.getId(), wmsSupplier.getId()});
                    if(null != sample){
                        if(recordList.size() == 0){
                            confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "ROHS!\n", UserHolder.getLanguage());
                        }else{
                            if(recordList.get(0).getRohsDate() != null){
                                //计算ROHS的时间
                                Date date = DateUtil.addHourToDate(recordList.get(0).getRohsDate(), Double.valueOf(sample.getRohsCycle() * 24));
                                int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                if(res == 1 || res == 0){
                                    //ROHS提醒
                                    confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "ROHS!\n", UserHolder.getLanguage());
                                }else{
                                    //不提醒
                                }
                                //confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "ROHS!\n", UserHolder.getLanguage());
                                /*if(WmsCycleSampleType.SHRQS.equals(sample.getSampleType())){
                                    //计算收货日期留样的时间
                                    Date date = DateUtil.addHourToDate(recordList.get(0).getSampleDate(), Double.valueOf(sample.getSampleCycle() * 24));
                                    int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                    if(res == 1 || res == 0){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[ROHS]需要收货日期留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                }else if(WmsCycleSampleType.SPS.equals(sample.getSampleType())){
                                    //计算首批留样的时间
                                    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
                                    String dateStr = format.format(recordList.get(0).getSampleDate());
                                    String receivedDate = format.format(wmsItemKey.getLotInfo().getReceivedDate());
                                    if(!dateStr.substring(4, 6).equals(receivedDate.substring(4, 6))){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[ROHS]需要首批留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                }else{
                                    //计算收货日期留样的时间
                                    Date date = DateUtil.addHourToDate(recordList.get(0).getRohsDate(), Double.valueOf(sample.getRohsCycle() * 24));
                                    int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                    if(res == 1 || res == 0){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[ROHS]需要收货日期留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                    confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "ROHS!\n", UserHolder.getLanguage());
                                }*/
                            }
                        }
                    }else{
                        throw new RfBusinessException("该货品[" + wmsItem.getCode() + "]供应商[" + wmsSupplier.getCode() + "]未找到有效的检验周期或留样数据！");
                    }
                }
                if(wmsItem.getBeORT()){
                    String hql = "from WmsInspectionRecord record where record.itemId = :itemId and record.supplierId = :supplierId and record.ortDate is not null  order by record.ortDate desc ";
                    String hql1 = "select sample from WmsCycleSample sample left join WmsSupplier supplier on supplier.id = sample.cycleSupplierId where supplier.code = :supplierCode " +
                            "and sample.cycleItemId = :itemId and sample.ortCycle is not null and sample.status = 'E' ";
                    WmsCycleSample sample = (WmsCycleSample) commonDao.findByQueryUniqueResult(hql1, new String[]{"supplierCode", "itemId"}, new Object[]{wmsSupplier.getCode(), wmsItem.getId()});
                    List<WmsInspectionRecord> recordList = commonDao.findByQuery(hql, new String[]{"itemId", "supplierId"}, new Object[]{wmsItem.getId(), wmsSupplier.getId()});
                    if(null != sample){
                        if(recordList.size() == 0){
                            confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "ORT!\n", UserHolder.getLanguage());
                        }else{
                            if(recordList.get(0).getOrtDate() != null){
                                //计算ORT的时间
                                Date date = DateUtil.addHourToDate(recordList.get(0).getOrtDate(), Double.valueOf(sample.getOrtCycle() * 24));
                                int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                if(res == 1 || res == 0){
                                    //ORT提醒
                                    confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "ORT!\n", UserHolder.getLanguage());
                                }else{
                                    //不提醒
                                }
                                /*if(WmsCycleSampleType.SHRQS.equals(sample.getSampleType())){
                                    //计算收货日期留样的时间
                                    Date date = DateUtil.addHourToDate(recordList.get(0).getSampleDate(), Double.valueOf(sample.getSampleCycle() * 24));
                                    int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                    if(res == 1 || res == 0){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[ORT]需要收货日期留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                }else if(WmsCycleSampleType.SPS.equals(sample.getSampleType())){
                                    //计算首批留样的时间
                                    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
                                    String dateStr = format.format(recordList.get(0).getSampleDate());
                                    String receivedDate = format.format(wmsItemKey.getLotInfo().getReceivedDate());
                                    if(!dateStr.substring(4, 6).equals(receivedDate.substring(4, 6))){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[ORT]需要首批留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                }else{
                                    confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "ORT!\n", UserHolder.getLanguage());
                                }*/
                            }
                        }
                    }else{
                        throw new RfBusinessException("该货品[" + wmsItem.getCode() + "]供应商[" + wmsSupplier.getCode() + "]未找到有效的检验周期或留样数据！");
                    }
                }
                if(wmsItem.getBeESD()){
                    String hql = "from WmsInspectionRecord record where record.itemId = :itemId and record.supplierId = :supplierId and record.esdDate is not null  order by record.esdDate desc ";
                    String hql1 = "select sample from WmsCycleSample sample left join WmsSupplier supplier on supplier.id = sample.cycleSupplierId where supplier.code = :supplierCode " +
                            "and sample.cycleItemId = :itemId and sample.esdCycle is not null and sample.status = 'E' ";
                    WmsCycleSample sample = (WmsCycleSample) commonDao.findByQueryUniqueResult(hql1, new String[]{"supplierCode", "itemId"}, new Object[]{wmsSupplier.getCode(), wmsItem.getId()});
                    List<WmsInspectionRecord> recordList = commonDao.findByQuery(hql, new String[]{"itemId", "supplierId"}, new Object[]{wmsItem.getId(), wmsSupplier.getId()});
                    if(null != sample){
                        if(recordList.size() == 0){
                            confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "ESD!\n", UserHolder.getLanguage());
                        }else{
                            if(recordList.get(0).getEsdDate() != null){
                                //计算ESD的时间
                                Date date = DateUtil.addHourToDate(recordList.get(0).getEsdDate(), Double.valueOf(sample.getEsdCycle() * 24));
                                int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                if(res == 1 || res == 0){
                                    //ESD提醒
                                    confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "ESD!\n", UserHolder.getLanguage());
                                }else{
                                    //不提醒
                                }
                                /*if(WmsCycleSampleType.SHRQS.equals(sample.getSampleType())){
                                    //计算收货日期留样的时间
                                    Date date = DateUtil.addHourToDate(recordList.get(0).getSampleDate(), Double.valueOf(sample.getSampleCycle() * 24));
                                    int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                    if(res == 1 || res == 0){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[ESD]需要收货日期留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                }else if(WmsCycleSampleType.SPS.equals(sample.getSampleType())){
                                    //计算首批留样的时间
                                    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
                                    String dateStr = format.format(recordList.get(0).getSampleDate());
                                    String receivedDate = format.format(wmsItemKey.getLotInfo().getReceivedDate());
                                    if(!dateStr.substring(4, 6).equals(receivedDate.substring(4, 6))){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[ESD]需要首批留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                }else{
                                    confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "ESD!\n", UserHolder.getLanguage());
                                }*/
                            }
                        }
                    }else{
                        throw new RfBusinessException("该货品[" + wmsItem.getCode() + "]供应商[" + wmsSupplier.getCode() + "]未找到有效的检验周期或留样数据！");
                    }
                }
                if(wmsItem.getBeSample()){
                    String hql = "from WmsInspectionRecord record where record.itemId = :itemId and record.supplierId = :supplierId and record.sampleDate is not null  order by record.sampleDate desc ";
                    String hql1 = "select sample from WmsCycleSample sample left join WmsSupplier supplier on supplier.id = sample.cycleSupplierId where supplier.code = :supplierCode " +
                            "and sample.cycleItemId = :itemId  and sample.status = 'E'";
                    WmsCycleSample sample = (WmsCycleSample) commonDao.findByQueryUniqueResult(hql1, new String[]{"supplierCode", "itemId"}, new Object[]{wmsSupplier.getCode(), wmsItem.getId()});
                    List<WmsInspectionRecord> recordList = commonDao.findByQuery(hql, new String[]{"itemId", "supplierId"}, new Object[]{wmsItem.getId(), wmsSupplier.getId()});
                    if(null != sample){
                        if(recordList.size() == 0){
                            if(!WmsCycleSampleType.BS.equals(sample.getSampleType())){
                                confirmCode = LocalizedMessage.getLocalizedMessage(confirmCode + "[留样]需要留样，留样"+ sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                            }
                        }else{
                            if(recordList.get(0).getSampleDate() != null){
                                if(WmsCycleSampleType.SHRQS.equals(sample.getSampleType())){
                                    //计算收货日期留样的时间
                                    if(sample.getSampleCycle() == null){
                                        throw new RfBusinessException("该货品[" + wmsItem.getCode() + "]供应商[" + wmsSupplier.getCode() + "]未找到有效的检验周期或留样数据！");
                                    }else{
                                        Date date = DateUtil.addHourToDate(recordList.get(0).getSampleDate(), Double.valueOf(sample.getSampleCycle() * 24));
                                        int res = wmsItemKey.getLotInfo().getReceivedDate().compareTo(date);//相等则返回0，date大返回1，否则返回-1
                                        if(res == 1 || res == 0){
                                            //留样提醒
                                            confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[留样]需要收货日期留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                        }else{
                                            //不留意
                                        }
                                    }
                                }else if(WmsCycleSampleType.SPS.equals(sample.getSampleType())){
                                    //计算首批留样的时间
                                    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
                                    String dateStr = format.format(recordList.get(0).getSampleDate());
                                    String receivedDate = format.format(wmsItemKey.getLotInfo().getReceivedDate());
                                    if(!dateStr.substring(4, 6).equals(receivedDate.substring(4, 6))){
                                        //留样提醒
                                        confirmCode =  LocalizedMessage.getLocalizedMessage(confirmCode + "[留样]需要首批留样，留样" + sample.getSampleCount() + "个!\n", UserHolder.getLanguage());
                                    }else{
                                        //不留意
                                    }
                                }
                            }
                        }
                    }else{
                        throw new RfBusinessException("该货品[" + wmsItem.getCode() + "]供应商[" + wmsSupplier.getCode() + "]未找到有效的检验周期或留样数据！");
                    }
                }
            }
        }
        return confirmCode;
    }

}
