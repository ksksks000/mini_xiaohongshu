package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Queue;

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

    /**
     * 关注或取关
     * @param followUserid
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserid, Boolean isFollow) {

        Long userId = UserHolder.getUser().getId();
        //1.判断取关还是关注
        if(isFollow){
            //2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserid);
            follow.setCreateTime(LocalDateTime.now());
            save(follow);
        }else{
            //3.取关，删除数据
            remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId)
                    .eq("follow_user_id",followUserid));
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
}
