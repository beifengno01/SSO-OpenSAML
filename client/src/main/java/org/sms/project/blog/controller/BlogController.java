package org.sms.project.blog.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.sms.SysConstants;
import org.sms.project.base.Result;
import org.sms.project.base.UploadFileBase;
import org.sms.project.blog.entity.Blog;
import org.sms.project.blog.service.BlogService;
import org.sms.project.common.ResultAdd;
import org.sms.project.helper.SessionHelper;
import org.sms.project.page.Page;
import org.sms.project.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author Sunny
 */
@Controller
@RequestMapping("/blog")
public class BlogController {

    @Autowired
    private BlogService blogService;

    @ResponseBody
    @RequestMapping(value = "/add", method = RequestMethod.POST)
    public ResultAdd add(Model model, @Valid Blog blog, HttpServletRequest request) {
        User user = (User) SessionHelper.get(request, SysConstants.LOGIN_USER);
        blog.setCreateUserId(user.getId());
        long count = blogService.insert(blog);
        ResultAdd resAdd = new ResultAdd();
        if (count == 0) {
            resAdd.setCode(0);
            resAdd.setError("数据格式错误");
            return resAdd;
        }
        resAdd.setCode(1);
        resAdd.setError("添加成功");
        return resAdd;
    }
    
    @ResponseBody
    @RequestMapping(value = "/upload", method = RequestMethod.POST)
    public List<UploadFileBase> upload(@RequestParam("file") MultipartFile file) {
        UploadFileBase resAdd = blogService.addUpload(file);
        List<UploadFileBase> res = new ArrayList<UploadFileBase>();
        res.add(resAdd);
        return res;
    }
    
    @ResponseBody
    @RequestMapping(value = "/upload", method = RequestMethod.GET)
    public ResultAdd uploadGet(HttpServletRequest request) {
        ResultAdd resAdd = new ResultAdd();
        resAdd.setCode(1);
        resAdd.setError("添加成功");
        List<ResultAdd> res = new ArrayList<ResultAdd>();
        res.add(resAdd);
        return resAdd;
    }

    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public String update(@Valid Blog blog, HttpServletRequest request) {
        return "login/login_success";
    }

    @RequestMapping(value = "/getById/{id}", method = RequestMethod.GET)
    public String getById(@PathVariable("id") Long id, HttpServletRequest request) {
        return "login/login_success";
    }

    @ResponseBody
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public Result<Blog> list(Model model, HttpServletRequest request) {
        String pageNumberStr = request.getParameter("pageNumber");
        String pageSizeStr = request.getParameter("pageSize");
        if (Objects.isNull(pageNumberStr) || Objects.isNull(pageSizeStr)) {
            return null;
        }
        Integer pageNumber = Integer.parseInt(pageNumberStr);
        Integer pageSize = Integer.parseInt(pageSizeStr);
        Page page = new Page(pageNumber, pageSize);
        List<Blog> blogs = blogService.queryByCondition(page);
        int pageCount = blogService.getCount();
        page.setRecordCount(pageCount);
        Result<Blog> res = new Result<Blog>();
        res.setPage(page);
        res.setList(blogs);
        return res;
    }
}