package com.database_termproject.twitter.ui.main.search;

import static com.database_termproject.twitter.utils.GlobalApplication.PASSWORD;
import static com.database_termproject.twitter.utils.GlobalApplication.URL;
import static com.database_termproject.twitter.utils.GlobalApplication.USER;
import static com.database_termproject.twitter.utils.SharedPreferenceManagerKt.getUserId;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.navigation.NavDirections;
import com.database_termproject.twitter.data.User;
import com.database_termproject.twitter.databinding.FragmentSearchBinding;
import com.database_termproject.twitter.ui.BaseFragment;
import com.database_termproject.twitter.ui.adapter.FollowRVAdapter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class SearchFragment extends BaseFragment<FragmentSearchBinding> {
    FollowRVAdapter followRVAdapter;

    @Override
    protected FragmentSearchBinding getBinding(LayoutInflater inflater, ViewGroup container) {
        return FragmentSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initAfterBinding() {
        // 검색 클릭 시,
        binding.searchIv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String searchWord = binding.searchEt.getText().toString();
                NavDirections action = SearchFragmentDirections.actionSearchFragmentToSearchAfterFragment(searchWord);
                findNavController().navigate(action);
            }
        });

        // 추천 계정
        followRVAdapter = new FollowRVAdapter(requireContext());
        binding.searchRecommendFollowRv.setAdapter(followRVAdapter);
        followRVAdapter.setMyClickListener(new FollowRVAdapter.MyItemClickListener() {
            @Override
            public void onUnWait(@NonNull User user) {
                new UnWaitAsyncTask().execute(""+user.user_id);
            }

            @Override
            public void onUnfollow(@NonNull User user) {
                new UnFollowAsyncTask().execute(""+user.user_id);
            }

            @Override
            public void onFollow(@NonNull User user) {
                new FollowAsyncTask().execute(""+user.user_id);
            }

            @Override
            public void onClick(@NonNull User user) {
                // TODO: 유저 페이지로 이동
            }
        });

        new GetRecommendedUserAsyncTask().execute();
    }

    // 추천 계정 JDBC
    @SuppressLint("StaticFieldLeak")
    public class GetRecommendedUserAsyncTask extends AsyncTask<Void, Void, ArrayList<User>> {
        @Override
        protected ArrayList<User> doInBackground(Void... voids) {
            String user_id = getUserId();
            String user_region = "";
            ArrayList<User> userList = new ArrayList<>();

            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                HashMap<String, Integer> similarity = new HashMap<>();

                // (0) 모든 유저 hashmap에 저장
                Statement stmt0 = connection.createStatement();
                String sql0 = "select id, region_id from user";
                ResultSet rs0 = stmt0.executeQuery(sql0);

                while(rs0.next()){
                    String id = rs0.getString("id");
                    similarity.put(id, 0);

                    if(id.equals(user_id)){
                        user_region = rs0.getString("region_id");
                    }
                }

                // (1) 동일한 관심사를 가진 유저들 +1씩
                Statement stmt = connection.createStatement();
                String sql = "select user_id from user_has_interest " +
                        "where interest_id in (select interest_id from user_has_interest where user_id = \"" + user_id + "\") " +
                        "and user_id != \"" + user_id + "\";";
                ResultSet rs = stmt.executeQuery(sql);

                while(rs.next()){
                    String id = rs.getString("user_id");
                    int prev = similarity.get(id);
                    similarity.replace(id, prev+1);
                }

                // (2) 동일한 지역에 사는 유저들 +1씩
                Statement stmt2 = connection.createStatement();
                String sql2 = "select id from user where region_id = \"" + user_region +"\" and id != \"" + user_id +"\";";
                ResultSet rs2 = stmt2.executeQuery(sql2);

                while(rs2.next()){
                    String id = rs2.getString("id");
                    int prev = similarity.get(id);
                    similarity.replace(id, prev+1);
                }
                
                // (3) 유사도 높은 상위 10개의 user 정보 조회하여 결과 리턴
                List<String> keyList = new ArrayList<>();
                keyList.addAll(similarity.keySet());

                Collections.sort(keyList, new Comparator<Object>() {
                    @Override
                    public int compare(Object o1, Object o2) {
                        Object v1 = similarity.get(o1);
                        Object v2 = similarity.get(o2);
                        return ((Comparable) v2).compareTo(v1);
                    }
                });

                for(String key: keyList){
                    Log.d("Search", key + "(" + similarity.get(key) + ")");
                }
                keyList.remove(user_id);

                Log.d("Search-Result", keyList.toString());

                for(int i=0; i<3; i++){
                    String id = keyList.get(i);
                    String query = "select id, nickname, image from user where id = \"" + id + "\"";
                    Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(query);

                    if(resultSet.next()){
                        String nickname = resultSet.getString("nickname"), image = resultSet.getString("image");
                        User user = new User(id, nickname, image);

                        // 팔로잉 상태 확인 - 기본(0), 팔로잉(1), 대기 중(2
                        String query2 = "select * from following where user_id = \"" + getUserId() + "\" and following_id = \"" + id + "\"";
                        Statement statement2 = connection.createStatement();
                        ResultSet resultSet2 = statement2.executeQuery(query2);

                        if(resultSet2.next()){
                            user.setFollowing(1);
                        }else{ // 팔로우 하지 않는 경우, 대기 중인지 확인
                            String query3 = "select * from wait_follow where user_id = \"" + id + "\" and follower_id = \"" + getUserId() + "\"";
                            Statement statement3 = connection.createStatement();
                            ResultSet resultSet3 = statement3.executeQuery(query3);

                            if(resultSet3.next()){
                                user.setFollowing(2);
                            }
                        }

                        userList.add(user);
                    }
                }


            } catch (Exception e) {
                Log.e("GetUserAsyncTask", "Error reading school information", e);
            }

            return userList;
        }

        @Override
        protected void onPostExecute(ArrayList<User> result) {
            if (result != null) {
                followRVAdapter.updateUser(result);
            }

            this.cancel(true);
        }
    }

    // 팔로우 JDBC
    @SuppressLint("StaticFieldLeak")
    public class FollowAsyncTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected Boolean doInBackground(String... strings) {
            String user_id = getUserId();
            String follow_id = strings[0];

            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                // 상대가 공개 계정인지, 비공개 계정인지 확인
                String sql1 = "select private from user where id = \"" + follow_id + "\"";
                Statement stmt1 = connection.createStatement();
                ResultSet rs1 =  stmt1.executeQuery(sql1);

                if(rs1.next()){
                    boolean isPrivate = rs1.getBoolean("private");

                    if(isPrivate){ // 비공개 계정인 경우, 팔로우 대기
                        String sql2 = "insert into wait_follow (user_id, follower_id) values (\"" + follow_id + "\", \"" + user_id + "\")";
                        PreparedStatement pstm = connection.prepareStatement(sql2);
                        pstm.executeUpdate();
                    }else{         // 공개 계정인 경우, 팔로우
                        String sql2 = "insert into follower values (\"" + follow_id + "\", \"" + user_id + "\")";
                        PreparedStatement pstm = connection.prepareStatement(sql2);
                        pstm.executeUpdate();

                        String sql3 = "insert into following values (\"" + user_id + "\", \"" + follow_id + "\")";
                        pstm.executeUpdate(sql3);
                    }

                    return true;
                }

            } catch (Exception e) {
                Log.e("GetUserAsyncTask", "Error reading school information", e);
            }

            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                // 다시 조회
                new GetRecommendedUserAsyncTask().execute();
            }

            this.cancel(true);
        }
    }


    // 언팔로우 JDBC
    @SuppressLint("StaticFieldLeak")
    public class UnFollowAsyncTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected Boolean doInBackground(String... strings) {
            String user_id = getUserId();
            String follow_id = strings[0];

            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                String sql1 = "delete from follower where user_id = \"" + follow_id + "\" and follower_id = \"" + user_id + "\"";
                PreparedStatement pstm = connection.prepareStatement(sql1);
                pstm.executeUpdate();

                String sql2 = "delete from following where user_id = \"" + user_id + "\" and following_id = \"" + follow_id + "\"";
                pstm.executeUpdate(sql2);

                return true;
            } catch (Exception e) {
                Log.e("GetUserAsyncTask", "Error reading school information", e);
            }

            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                // 다시 조회
                new GetRecommendedUserAsyncTask().execute();
            }

            this.cancel(true);
        }
    }

    // 대기 취소 JDBC
    @SuppressLint("StaticFieldLeak")
    public class UnWaitAsyncTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected Boolean doInBackground(String... strings) {
            String user_id = getUserId();
            String follow_id = strings[0];

            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                String sql1 = "delete from wait_follow where user_id = \"" + follow_id + "\" and follower_id = \"" + user_id + "\"";
                PreparedStatement pstm = connection.prepareStatement(sql1);
                pstm.executeUpdate();

                return true;
            } catch (Exception e) {
                Log.e("GetUserAsyncTask", "Error reading school information", e);
            }

            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                // 다시 조회
                new GetRecommendedUserAsyncTask().execute();
            }

            this.cancel(true);
        }
    }
}
