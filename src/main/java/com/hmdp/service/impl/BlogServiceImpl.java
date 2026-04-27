package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }



    @Override
    public Result queryBlogById(Long id) {

        //查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);
        //查询当前博客是否被登录用户点赞，来回显给登录用户
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    //查询当前博客是否被登录用户点赞，来回显给登录用户
    private void isBlogLiked(Blog blog) {
        //获取当前用户id
        //注意，如果说当前没有用户登录，会报空指针异常，
        //你试图调用 UserDTO.getId() 方法，但是 UserHolder.getUser() 返回了 null
        UserDTO user = UserHolder.getUser();
        // 【重要修复】先判断用户是否登录，防止空指针异常
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        Long blogId = blog.getId();
        String key = "blog:liked:" + blogId;
        //查询Redis的set集合中是否有记录
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        // 设置 blog 的 liked 状态回显给用户
        blog.setIsLike(score != null);

    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

/*    @Override
    //这个架构因为Redis和数据库操作是绑定在一起的，严重影响了Redis的性能，所以不采用
    public Result likeBlog(Long id) {

        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //2.判断用户是否已点赞
        String key = "blog:liked:" + id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isMember)){
            //3.1用户未点赞，允许点赞，
            //数据库点赞+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //并且把用户放到Redis的set集合中
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }

        }else{
            //3.2用户已点赞，取消点赞，
            //数据库点赞-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();

            //把Redis的set集合中的用户删除
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }*/

    /**
     * Redis和 数据库 操作分开，释放Redis的效率
     * 原理：
     * 直接把点赞和取消点赞动作产生的影响放进Redis中的set
     * 并且利用set集合的唯一性，标记 脏数据，即有修改的数据，交给后续数据库审查
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取登录用户 id
        Long userId = UserHolder.getUser().getId();
        // 2. 定义 Redis Key
        String key = "blog:liked:" + id;

        // 3. 判断用户是否已点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            // 4.1 用户未点赞，执行点赞
            // 只操作 Redis，添加用户 ID 到集合
            stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());

            // 【新增】标记该博客的点赞数发生了变动，方便定时任务扫描
            stringRedisTemplate.opsForSet().add("blog:liked:dirty", id.toString());

        } else {
            // 4.2 用户已点赞，取消点赞
            // 只操作 Redis，从集合中移除用户 ID
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());

            // 【新增】同样标记为变动（防止取消后数量为0，但数据库还没更新的情况）
            stringRedisTemplate.opsForSet().add("blog:liked:dirty", id.toString());
        }

        // 5. 直接返回，不操作数据库，速度极快
        return Result.ok();
    }

    // 该方法用于查询点赞排行榜 Top 5
    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        // 1. 从 SortedSet 中查询前 5 名点赞用户 (下标 0 到 4)
        // 因为 score 是时间戳，所以这是最早点赞的 5 个人
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2. 解析出其中的用户 id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        // 3. 根据用户 id 查询用户信息
        // 【关键】使用 ORDER BY FIELD 保证数据库查询结果的顺序和 Redis 中的顺序一致
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4. 返回结果
        return Result.ok(userDTOS);
    }



    /**
     * 定时任务：将 Redis 中的点赞数据同步到数据库
     * 每 5 秒执行一次
     */
    @Scheduled(fixedDelay = 5000)
    public void syncLikeToDatabase() {
        // 1. 获取所有“被修改过”的博客 ID (脏数据列表)
        Set<String> dirtyBlogIds = stringRedisTemplate.opsForSet().members("blog:liked:dirty");

        // 如果没有变动的博客，直接返回
        if (dirtyBlogIds == null || dirtyBlogIds.isEmpty()) {
            return;
        }

        // 2. 遍历每一个被修改的博客
        for (String blogIdStr : dirtyBlogIds) {
            Long blogId = Long.valueOf(blogIdStr);
            String key = "blog:liked:" + blogId;

            // 3. 获取 Redis 中该博客所有点赞用户的集合
            // 这一步是为了拿到最准确的“最终状态”
            Set<String> likedUsers = stringRedisTemplate.opsForSet().members(key);

            // 4. 计算真实的点赞数量
            int realCount = (likedUsers == null) ? 0 : likedUsers.size();

            // 5. 直接更新数据库（覆盖写，而不是增量写）
            // 这样无论中间经历了多少次点赞/取消，数据库最终都会变成正确的数字
            update().set("liked", realCount).eq("id", blogId).update();

            // 6. 同步完成后，从“脏数据列表”中移除该博客 ID
            stringRedisTemplate.opsForSet().remove("blog:liked:dirty", blogIdStr);
        }
    }
}
