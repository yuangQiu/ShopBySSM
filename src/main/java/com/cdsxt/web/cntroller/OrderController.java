package com.cdsxt.web.cntroller;

import com.alipay.api.AlipayApiException;
import com.cdsxt.ro.OrderInfo;
import com.cdsxt.ro.User;
import com.cdsxt.ro.UserAddress;
import com.cdsxt.service.CartService;
import com.cdsxt.service.OrderService;
import com.cdsxt.service.UserService;
import com.cdsxt.util.HttpUtils;
import com.cdsxt.util.JsonUtil;
import com.cdsxt.vo.LogisticsInfo;
import com.cdsxt.vo.ProductInCart;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

@RequestMapping("order")
@Controller
public class OrderController {


    @Autowired
    private CartService cartService;

    @Autowired
    private CartController cartController;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private Alipay alipay;

    /**
     * 返回订单页面
     *
     * @param ids 需要结算的商品
     * @return 到订单页面
     */
    @RequestMapping(value = "checkIn", method = RequestMethod.GET)
    public String checkIn(Integer[] ids, HttpServletRequest request, Model model) {
        // 获取当前用户
        User uf = (User) request.getSession().getAttribute("curUser");

        String productInCart = this.cartService.getCartStrFromRedis(uf.getUsername());

        // 解析购物车字符串, 与订单中的商品 id 进行比较, 相同则表示该商品需要结算, 就返回到页面上
        List<ProductInCart> productsInOrders = new ArrayList<>();
        // 计算总价
        double sumPrice = 0;
        if (StringUtils.hasText(productInCart)) {
            List<ProductInCart> productsInOrder = Arrays.asList(JsonUtil.jsonStrToArr(productInCart, ProductInCart.class));
            // 对订单数组进行排序
            Arrays.sort(ids);
            for (int i = 0; i < productsInOrder.size(); i++) {
                ProductInCart pic = productsInOrder.get(i);
                if (Arrays.binarySearch(ids, pic.getPid()) >= 0) {
                    // 找到, 表示该商品在订单中
                    productsInOrders.add(pic);
                    sumPrice += pic.getShopPrice() * pic.getCount();
                }
            }
            model.addAttribute("productsInOrder", productsInOrders);
            model.addAttribute("sumPrice", sumPrice);
        }
        // 存入用户的收获地址信息
        model.addAttribute("userAddresses", this.userService.selectUserAddressById(uf.getUid()));
        // 将需要结算的商品 id 返回, 以便下次获取
        model.addAttribute("ids", Arrays.toString(ids).substring(1, Arrays.toString(ids).length() - 1));
        return "front/order";
    }


    /**
     * 生成订单
     *
     * @param ids       已下单的商品
     * @param address   收获地址 id
     * @param payMethod 付款方式
     * @return
     */
    // 获取订单信息, 并存入到 order_info 表中
    @RequestMapping(value = "checkIn", method = RequestMethod.POST)
    @ResponseBody
    public String checkIn(Integer[] ids, Integer address, String payMethod, HttpServletRequest request,
                          HttpServletResponse response) throws IOException, AlipayApiException {
        // 获取当前用户
        User uf = (User) request.getSession().getAttribute("curUser");

        String productInCart = this.cartService.getCartStrFromRedis(uf.getUsername());

        // 要支付的订单信息
        List<ProductInCart> productsInOrders = new ArrayList<>();
        // 购物车中剩余商品
        List<ProductInCart> productsInCartLeave = new ArrayList<>();
        if (StringUtils.hasText(productInCart)) {
            List<ProductInCart> productsInOrder = Arrays.asList(JsonUtil.jsonStrToArr(productInCart, ProductInCart.class));
            Arrays.sort(ids);
            for (int i = 0; i < productsInOrder.size(); i++) {
                ProductInCart pic = productsInOrder.get(i);
                if (Arrays.binarySearch(ids, pic.getPid()) >= 0) {
                    productsInOrders.add(pic);
                } else {
                    // 订单以外的商品加入到剩余商品列表中
                    productsInCartLeave.add(pic);
                }
            }
        }
        UserAddress userAddress = this.userService.selectAddressById(address);
        // 生成订单数据, 保存在数据库中, 返回主键
        String oid = this.orderService.generateOrder(productsInOrders, userAddress, uf);

        // 1. 需要在提交订单后, 同步修改 redis 和 cookie 中的数据, 此处先修改 redis 中的数据;
        // 待支付完成后跳转到 returnUrl() 中再更新 cookie, 以便响应 cookie
        String cartString = JsonUtil.objToJsonStr(productsInCartLeave);
        this.cartService.setCartStrToRedis(uf.getUsername(), cartString);
        // 2. 更新 cookie 中的数据
        this.cartController.setCartToCookie(cartString, response);

        // 根据 payMethod 调用不同的支付方法
        if ("alipay".equals(payMethod)) {
            // 调用支付宝付款
            // 写出页面, 进行付款
            return alipay.pay(oid);
        } else if ("wechat".equals(payMethod)) {
            return "<h2>微信付款</h2>";
        } else {
            return "其他方式";
        }

    }

    // 查询当前用户的所有订单信息, 返回到 "我的订单" 中显示
    @RequestMapping(value = "selectAllOrder", method = RequestMethod.GET)
    public String selectAllOrder(Model model, HttpServletRequest request) {
        User uf = (User) request.getSession().getAttribute("curUser");
        List<OrderInfo> allOrders = this.orderService.selectAllOrder(uf.getUid());
        model.addAttribute("allOrders", allOrders);
        return "front/allOrder";
    }


    // 重新付款: 对未付款订单
    @RequestMapping(value = "repay")
    @ResponseBody
    public String repay(String oid) throws UnsupportedEncodingException, AlipayApiException {
        return alipay.pay(oid);
    }


    /**
     * 根据时间, 状态以及收货人查询订单
     *
     * @param orderTimeMin 下单时间开始日期
     * @param orderTimeMax 下单时间截止日期
     * @param name         收货人
     * @param state        订单状态
     * @return
     */
    @RequestMapping(value = "selectOrderWithParam")
    public String selectOrderWithParam(@DateTimeFormat(pattern = "yyyy-MM-dd") Date orderTimeMin,
                                       @DateTimeFormat(pattern = "yyyy-MM-dd") Date orderTimeMax,
                                       String name, String state, Model model, HttpServletRequest request) {
        Map<String, Object> params = new HashMap<>();
        params.put("orderTimeMin", orderTimeMin);
        params.put("orderTimeMax", orderTimeMax);
        params.put("name", name);
        params.put("state", state);
        // 当前登陆用户
        User uf = (User) request.getSession().getAttribute("curUser");
        // 指定查询哪个用户
        params.put("uid", uf.getUid());

        List<OrderInfo> orders = this.orderService.selectOrderWithParam(params);
        model.addAttribute("allOrders", orders);
        return "front/allOrder";
    }

    /**
     * 查询物流信息
     */
    @RequestMapping(value = "queryLogistics", method = RequestMethod.GET)
    public String queryLogistics(String logisticsComp, String logisticsNum, Model model) {
        String logicInfo = this.queryApiByAli(logisticsComp, logisticsNum);
        LogisticsInfo logisticsInfo = new LogisticsInfo();
        LinkedHashMap result = (LinkedHashMap) JsonUtil.jsonStrToMap(logicInfo).get("result");
        logisticsInfo.setResults((List<Map<String, String>>) result.get("list"));
        logisticsInfo.setNumber((String) result.get("number"));
        logisticsInfo.setType((String) result.get("type"));

        model.addAttribute("logisticsInfo", logisticsInfo);
        return "front/logisticsResult";
    }

    // 调用 快递查询 API 的方法
    private String queryApiByAli(String logisticsComp, String logisticsNum) {
        String host = "http://jisukdcx.market.alicloudapi.com";
        String path = "/express/query";
        String method = "GET";
        String appcode = "d0a4cc9b736745748a11673310ea956c";
        Map<String, String> headers = new HashMap<>();
        //最后在header中的格式(中间是英文空格)为Authorization:APPCODE 83359fd73fe94948385f570e3c139105
        headers.put("Authorization", "APPCODE " + appcode);
        Map<String, String> querys = new HashMap<>();
        querys.put("number", logisticsNum);
        querys.put("type", logisticsComp);


        try {
            HttpResponse response = HttpUtils.doGet(host, path, method, headers, querys);
            System.out.println(response.toString());
            //获取response的body
            return EntityUtils.toString(response.getEntity());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
