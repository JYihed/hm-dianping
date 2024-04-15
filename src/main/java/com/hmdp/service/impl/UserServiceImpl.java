package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author chenjy
 * @since 2024-04-15
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String utoken = "";

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("验证码发送成功，验证码：{}",code);
        //返回ojbk
        return Result.ok();
    }




    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
       //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(cachecode == null || !cachecode.equals(code)){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user=query().eq("phone",phone).one();

        //5.判断用户是否存在
        if(user==null){
            //6.不存在，创建新用户并保存
             user = creaUserWithPhone(phone);
        }

        //7.存在
        //8.保存用户信息到redis
        //8.1 生成token作为令牌
        String token = UUID.randomUUID().toString();
        utoken = token;
        //8.2将user对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //8.3存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        //8.4设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //8.4返回token

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime now = LocalDateTime.now();

        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId;

        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //5.写入Redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime now = LocalDateTime.now();

        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId;

        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //5.获取本月截至今天为止的所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }

        //6.循环遍历
        int count = 0;
        while (true){
            //7.让这个数字与1做与运算，得到数字的最后一个bit位
            if ((num & 1) == 0) {
                // 如果为0，未签到，结束
                break;
            }else {
                // 如果不为0 ，已签到，计数器+1
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位
            num >>>=1;
        }
        return Result.ok(count);
    }

    @Transactional
    @Override
    public Result logout() {
        Boolean result = stringRedisTemplate.delete(LOGIN_USER_KEY + utoken);
        if(result){
            return Result.ok();
        }
        //System.out.println("key = " + LOGIN_USER_KEY + utoken);
        return Result.fail(LOGIN_USER_KEY + utoken);
    }

    private User creaUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //2.保存用户
        save(user);
        return user;
    }
}
