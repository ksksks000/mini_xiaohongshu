package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOWS_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    /**
     * 关注或取关
     * @param followUserid
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserid, Boolean isFollow) {

        Long userId = UserHolder.getUser().getId();

        String key = FOLLOWS_KEY + userId;
        //1.判断取关还是关注
        if(isFollow){
            //2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserid);
            follow.setCreateTime(LocalDateTime.now());
            boolean isSuccess  = save(follow);
            if(isSuccess){
                //把关注用户的id，放入Redis的set集合中
                stringRedisTemplate.opsForSet().add(key,followUserid.toString());
            }
        }else{
            //3.取关，删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserid));
            if (isSuccess){
                //把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key,followUserid.toString());
            }

        }
        return Result.ok();
    }

    /**
     * 是否关注
     * @param followUserid
     * @return
     */
    @Override
    public Result isFollow(Long followUserid) {
        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询是否存在关注关系
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserid)
                .count();
        //3.返回
        return Result.ok(count > 0);
    }

    /**
     * 查询共同id
     * @param id
     * @return
     */
    @Override
    public Result followCommon(Long id) {
        //1.获取当前用户key
        Long userId = UserHolder.getUser().getId();
        String key1 = FOLLOWS_KEY + userId;
        //2.获取目标用户key
        String key2 = FOLLOWS_KEY + id;
        //3.求两个用户的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()){
            //无交集
            return Result.ok(Collections.emptyList());
        }
        //4.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //5查询用户
        List<UserDTO> collect = userService.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(collect);
    }
}
